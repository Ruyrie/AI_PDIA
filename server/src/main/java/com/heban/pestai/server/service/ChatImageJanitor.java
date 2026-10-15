package com.heban.pestai.server.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.UserMapper;

/**
 * 定期清理上传后没有被引用的图片（两类都是「先上传、后保存」，上传接口不写数据库）：
 * <ul>
 *   <li>未发送的聊天图片：App 选图后立即上传（POST files/image），用户点 ✕ 删除或最终没有发送时，
 *       图片留在 {文件根目录}/chat/{yyyyMMdd}/ 下却不被任何消息引用（t_ai_message_image 或 t_ai_message.image_url 任一处）；</li>
 *   <li>未保存的头像：App 选好头像后立即上传（POST user/avatar），用户最终没有保存资料时，
 *       图片留在 avatar/{yyyyMMdd}/ 下却不被任何账号的 t_user.avatar_url 引用；换头像时旧头像由 UserService
 *       在保存后直接删除，这里兜底清理当时没删掉的。</li>
 * </ul>
 * 每天 04:30（Asia/Shanghai，pestai.ai.orphan-image-cron 可调整，「-」关闭）先清理聊天图片、再清理头像，
 * 各自扫描日期不晚于前天的目录（保留今天和昨天，给正在编辑的问题 / 资料留出时间）：
 * <ol>
 *   <li>目录中的 jpg / png 按 500 个一批核对是否仍被引用，未被引用的删除；核对失败（如数据库不可用）时
 *       这一类本次不再删除任何图片；</li>
 *   <li>上传中断留下的「.upload-*.tmp」临时文件直接删除；</li>
 *   <li>删空的日期目录一并删除；目录名不是 yyyyMMdd 日期的一律跳过，其他类型的文件不动。</li>
 * </ol>
 * 任何失败只记日志、不抛出异常；每类每次结束记一行 INFO 汇总。
 */
@Component
public class ChatImageJanitor {

    private static final Logger log = LoggerFactory.getLogger(ChatImageJanitor.class);

    /** 每批核对的相对路径数（IN 列表长度） */
    static final int BATCH_SIZE = 500;
    /** 只清理日期不晚于「今天 - 2 天」的目录（今天、昨天的保留） */
    static final int KEEP_DAYS = 2;

    private static final Pattern DAY_DIR = Pattern.compile("\\d{8}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final Kind chat;
    private final Kind avatar;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    public ChatImageJanitor(AiMessageMapper aiMessageMapper, UserMapper userMapper,
                            FileStorageService fileStorageService, Clock clock) {
        this.chat = new Kind(FileStorageService.CHAT, "未发送的聊天图片", aiMessageMapper::selectImageUrlsInUse);
        this.avatar = new Kind(FileStorageService.AVATAR, "未保存的头像", userMapper::selectAvatarUrlsInUse);
        this.fileStorageService = fileStorageService;
        this.clock = clock;
    }

    @Scheduled(cron = "${pestai.ai.orphan-image-cron:0 30 4 * * *}", zone = "Asia/Shanghai")
    public void cleanOrphanImages() {
        clean();
        cleanAvatars();
    }

    /** 清理一次未发送的聊天图片并返回统计（定时任务与单元测试共用）；不抛出异常 */
    Result clean() {
        return clean(chat);
    }

    /** 清理一次未保存的头像并返回统计；不抛出异常 */
    Result cleanAvatars() {
        return clean(avatar);
    }

    private Result clean(Kind kind) {
        Result result = new Result();
        try {
            Path categoryRoot = fileStorageService.getRoot().resolve(kind.category());
            if (Files.isDirectory(categoryRoot)) {
                LocalDate latest = LocalDate.now(clock).minusDays(KEEP_DAYS);
                for (Path dir : dayDirectories(categoryRoot, latest)) {
                    if (!cleanDirectory(kind, dir, result)) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("清理{}失败：{}", kind.label(), e.getMessage());
        }
        log.info("清理{}：检查日期目录 {} 个、图片 {} 张，删除未引用图片 {} 张、临时文件 {} 个、空目录 {} 个", kind.label(),
                result.directories, result.scanned, result.deletedImages, result.deletedTemps, result.removedDirectories);
        return result;
    }

    /** 类别目录（chat/、avatar/）下名称为 yyyyMMdd 且日期不晚于 latest 的目录，按名称排序 */
    private static List<Path> dayDirectories(Path categoryRoot, LocalDate latest) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(categoryRoot)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || !DAY_DIR.matcher(name).matches()) {
                    continue;
                }
                try {
                    if (!LocalDate.parse(name, DAY).isAfter(latest)) {
                        dirs.add(dir);
                    }
                } catch (DateTimeParseException e) {
                    // 8 位数字但不是合法日期（如 20261340），跳过
                }
            }
        }
        dirs.sort(null);
        return dirs;
    }

    /**
     * 清理一个日期目录；核对引用失败时返回 false（这一类本次清理到此为止）。
     * 相对路径与入库时一致：{类别}/{yyyyMMdd}/{文件名}，如 chat/20261012/xxx.jpg、avatar/20261012/xxx.png。
     */
    private boolean cleanDirectory(Kind kind, Path dir, Result result) {
        String prefix = kind.category() + "/" + dir.getFileName() + "/";
        List<String> images = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jpg") || lower.endsWith(".png")) {
                    images.add(prefix + name);
                } else if (name.startsWith(".upload-") && name.endsWith(".tmp")
                        && fileStorageService.deleteQuietly(prefix + name)) {
                    result.deletedTemps++;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("清理{}：读取目录 {} 失败：{}", kind.label(), dir, e.getMessage());
            return true;
        }
        result.directories++;
        result.scanned += images.size();
        images.sort(null);
        for (int from = 0; from < images.size(); from += BATCH_SIZE) {
            List<String> batch = images.subList(from, Math.min(images.size(), from + BATCH_SIZE));
            Set<String> inUse;
            try {
                List<String> rows = kind.inUse().apply(batch);
                if (rows == null) {
                    throw new IllegalStateException("核对结果为空");
                }
                inUse = new HashSet<>(rows);
            } catch (RuntimeException e) {
                log.warn("清理{}：核对图片引用失败，本次不再删除：{}", kind.label(), e.getMessage());
                return false;
            }
            for (String path : batch) {
                if (!inUse.contains(path) && fileStorageService.deleteQuietly(path)) {
                    result.deletedImages++;
                }
            }
        }
        removeIfEmpty(dir, result);
        return true;
    }

    private static void removeIfEmpty(Path dir, Result result) {
        try {
            boolean empty;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                empty = !stream.iterator().hasNext();
            }
            if (empty) {
                Files.delete(dir);
                result.removedDirectories++;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("删除空目录 {} 失败：{}", dir, e.getMessage());
        }
    }

    /** 一类待清理的图片：类别目录、日志中的名称、按相对路径批量查询仍被引用的那些 */
    private record Kind(String category, String label, Function<List<String>, List<String>> inUse) {
    }

    /** 一次清理的统计 */
    static final class Result {
        int directories;
        int scanned;
        int deletedImages;
        int deletedTemps;
        int removedDirectories;
    }
}
