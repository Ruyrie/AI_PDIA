package com.heban.pestai.server.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.PestaiProperties;

/**
 * 文件存储：{根目录}/{类别}/{yyyyMMdd}/{uuid}.jpg（PNG 为 .png），类别 avatar / rec / chat。
 * 只接受 jpg / png，单文件不超过 2MB，并核对文件头；数据库只存相对路径，组装 VO 时拼成 {public-base-url}/files/ + 相对路径。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    public static final String AVATAR = "avatar";
    public static final String REC = "rec";
    public static final String CHAT = "chat";
    private static final Set<String> CATEGORIES = Set.of(AVATAR, REC, CHAT);

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final String MIME_JPEG = "image/jpeg";
    public static final String MIME_PNG = "image/png";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String FILES_SEGMENT = "/files/";

    private final Path root;
    private final String filesPrefix;
    private final Clock clock;

    @Autowired
    public FileStorageService(PestaiProperties properties, Clock clock) {
        this(resolveRoot(properties.getFileRoot()), properties.getPublicBaseUrl(), clock);
    }

    public FileStorageService(Path root, String publicBaseUrl, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.filesPrefix = trimTrailingSlash(publicBaseUrl) + FILES_SEGMENT;
        this.clock = clock;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件根目录：" + this.root, e);
        }
    }

    /** 未配置时：Windows 用 ./data/files，其他系统用 /data/pestai/files */
    public static Path resolveRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim());
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? Paths.get("data", "files") : Paths.get("/data/pestai/files");
    }

    public Path getRoot() {
        return root;
    }

    /** 校验并保存上传的图片，返回相对路径（如 rec/20261013/{uuid}.jpg） */
    public String save(MultipartFile file, String category) {
        return save(readImage(file), category);
    }

    /**
     * 校验上传的图片并读出字节：不能为空、不超过 2MB、Content-Type 为 image/jpeg 或 image/png，
     * 文件头确实是 JPEG 或 PNG（挡住改了扩展名的其他文件）。不通过返回 40001。
     */
    public byte[] readImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.param("请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.param("图片不能超过 2MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith(MIME_JPEG) && !contentType.startsWith("image/jpg")
                && !contentType.startsWith(MIME_PNG)) {
            throw BizException.param("只支持 jpg、png 格式的图片");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BizException.param("图片读取失败");
        }
        if (detectMime(bytes) == null) {
            throw BizException.param("只支持 jpg、png 格式的图片");
        }
        return bytes;
    }

    /** 校验文件头后写入磁盘（先写临时文件再重命名，避免读到写了一半的文件） */
    public String save(byte[] bytes, String category) {
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("未知的文件类别：" + category);
        }
        if (bytes == null || bytes.length == 0) {
            throw BizException.param("请选择图片");
        }
        if (bytes.length > MAX_BYTES) {
            throw BizException.param("图片不能超过 2MB");
        }
        String mime = detectMime(bytes);
        if (mime == null) {
            throw BizException.param("只支持 jpg、png 格式的图片");
        }
        String ext = MIME_PNG.equals(mime) ? ".png" : ".jpg";
        String relative = category + "/" + LocalDate.now(clock).format(DAY) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = root.resolve(relative).normalize();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("写入文件失败：{}", target, e);
            throw new BizException(ErrorCode.SERVER_ERROR);
        }
        return relative;
    }

    /** 读取相对路径对应的文件；文件不存在抛出 NoSuchFileException */
    public byte[] readBytes(String relative) throws IOException {
        Path path = resolveSafely(relative);
        if (path == null || !Files.isRegularFile(path)) {
            throw new NoSuchFileException(String.valueOf(relative));
        }
        return Files.readAllBytes(path);
    }

    public boolean exists(String relative) {
        Path path = resolveSafely(relative);
        return path != null && Files.isRegularFile(path);
    }

    /**
     * 尽力删除相对路径对应的文件（注销账号时清理头像、识别照片、聊天图片）。只处理以 avatar/、rec/、chat/ 开头、
     * 位于文件根目录内的普通文件（完整外链、目录、越界路径一律跳过）；文件本来就不存在按已删除处理。
     * 任何失败只记日志，不抛出异常。
     *
     * @return 文件已不存在（删除成功或本来就没有）时为 true
     */
    public boolean deleteQuietly(String relative) {
        if (relative == null || relative.isBlank()) {
            return false;
        }
        int slash = relative.indexOf('/');
        if (slash <= 0 || !CATEGORIES.contains(relative.substring(0, slash))) {
            log.warn("跳过删除：不是本服务保存的文件路径：{}", relative);
            return false;
        }
        try {
            Path path = resolveSafely(relative);
            if (path == null || Files.isDirectory(path)) {
                log.warn("跳过删除：路径无效：{}", relative);
                return false;
            }
            Files.deleteIfExists(path);
            return true;
        } catch (IOException | RuntimeException e) {
            // RuntimeException 包括 InvalidPathException（路径含非法字符）、SecurityException
            log.warn("删除文件失败：{}（{}）", relative, e.getMessage());
            return false;
        }
    }

    /** 相对路径 → 完整地址；为空时返回 null */
    public String toUrl(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative;
        }
        return filesPrefix + (relative.startsWith("/") ? relative.substring(1) : relative);
    }

    /**
     * App 传回的完整地址 → 相对路径：取 /files/ 之后的部分，要求以「类别/」开头、不含上级目录跳转、文件在磁盘上存在，
     * 否则返回 null。只入库相对路径，因此无法借此写入任意外链。
     */
    public String toRelative(String url, String category) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path;
        String trimmed = url.trim();
        if (trimmed.startsWith(filesPrefix)) {
            path = trimmed.substring(filesPrefix.length());
        } else {
            try {
                String rawPath = URI.create(trimmed).getPath();
                int index = rawPath == null ? -1 : rawPath.indexOf(FILES_SEGMENT);
                if (index < 0) {
                    return null;
                }
                path = rawPath.substring(index + FILES_SEGMENT.length());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.startsWith(category + "/") || path.contains("..") || path.contains("\\")) {
            return null;
        }
        return exists(path) ? path : null;
    }

    /** 按文件头判断类型：JPEG（FF D8 FF）、PNG（89 50 4E 47 0D 0A 1A 0A） */
    public static String detectMime(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return MIME_JPEG;
        }
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (bytes.length >= png.length) {
            for (int i = 0; i < png.length; i++) {
                if (bytes[i] != png[i]) {
                    return null;
                }
            }
            return MIME_PNG;
        }
        return null;
    }

    /** 按扩展名推断类型（读取已保存文件时使用） */
    public static String mimeOfPath(String relative) {
        return relative != null && relative.toLowerCase(Locale.ROOT).endsWith(".png") ? MIME_PNG : MIME_JPEG;
    }

    private Path resolveSafely(String relative) {
        if (relative == null || relative.isBlank() || relative.contains("..")) {
            return null;
        }
        Path path = root.resolve(relative).normalize();
        return path.startsWith(root) ? path : null;
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
