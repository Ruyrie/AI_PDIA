package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

class FileStorageServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @TempDir
    Path root;

    private FileStorageService files;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        files = new FileStorageService(root, "http://10.0.2.2:8080/", clock);
    }

    @Test
    void savesUnderCategoryAndDateWithUuidName() throws Exception {
        String relative = files.save(new MockMultipartFile("avatar", "a.jpg", "image/jpeg", JPEG), FileStorageService.AVATAR);

        assertThat(relative).matches("avatar/20261013/[0-9a-f]{32}\\.jpg");
        assertThat(files.readBytes(relative)).isEqualTo(JPEG);
        assertThat(files.toUrl(relative)).isEqualTo("http://10.0.2.2:8080/files/" + relative);
        assertThat(files.save(PNG, FileStorageService.CHAT)).endsWith(".png");
    }

    @Test
    void rejectsWrongTypeFakeHeaderEmptyAndOversizedFiles() {
        assertParamError(() -> files.save(new MockMultipartFile("a", "a.gif", "image/gif", JPEG), FileStorageService.CHAT));
        assertParamError(() -> files.save(new MockMultipartFile("a", "a.jpg", "image/jpeg", "<html>".getBytes()),
                FileStorageService.CHAT));
        assertParamError(() -> files.save(new MockMultipartFile("a", "a.jpg", "image/jpeg", new byte[0]),
                FileStorageService.CHAT));
        byte[] big = new byte[(int) FileStorageService.MAX_BYTES + 1];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        assertParamError(() -> files.save(new MockMultipartFile("a", "a.jpg", "image/jpeg", big), FileStorageService.CHAT));
    }

    @Test
    void urlToRelativeRequiresCategoryNoTraversalAndExistingFile() {
        String relative = files.save(JPEG, FileStorageService.AVATAR);

        assertThat(files.toRelative("http://10.0.2.2:8080/files/" + relative, FileStorageService.AVATAR)).isEqualTo(relative);
        // 局域网真机访问时地址主机不同，只取 /files/ 之后的路径
        assertThat(files.toRelative("http://192.168.1.20:8080/files/" + relative, FileStorageService.AVATAR))
                .isEqualTo(relative);
        assertThat(files.toRelative("http://10.0.2.2:8080/files/" + relative, FileStorageService.CHAT)).isNull();
        assertThat(files.toRelative("https://evil.example.com/a.jpg", FileStorageService.AVATAR)).isNull();
        assertThat(files.toRelative("http://10.0.2.2:8080/files/avatar/../../etc/passwd", FileStorageService.AVATAR)).isNull();
        assertThat(files.toRelative("http://10.0.2.2:8080/files/avatar/20261013/none.jpg", FileStorageService.AVATAR)).isNull();
    }

    @Test
    void readingMissingOrEscapingPathFails() {
        assertThatThrownBy(() -> files.readBytes("rec/20261013/none.jpg")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> files.readBytes("../secret.txt")).isInstanceOf(NoSuchFileException.class);
        assertThat(files.toUrl(null)).isNull();
    }

    @Test
    void deleteQuietlyRemovesOwnFilesAndSkipsEverythingElse() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        FileStorageService nested = new FileStorageService(root.resolve("files"), "http://10.0.2.2:8080", clock);
        String avatar = nested.save(JPEG, FileStorageService.AVATAR);
        String chat = nested.save(PNG, FileStorageService.CHAT);
        Path outside = root.resolve("outside.jpg");
        Files.write(outside, JPEG);

        assertThat(nested.deleteQuietly(avatar)).isTrue();
        assertThat(nested.exists(avatar)).isFalse();
        // 已经不存在：按已删除处理，不报错
        assertThat(nested.deleteQuietly(avatar)).isTrue();

        assertThat(nested.deleteQuietly(null)).isFalse();
        assertThat(nested.deleteQuietly(" ")).isFalse();
        assertThat(nested.deleteQuietly("http://10.0.2.2:8080/files/" + chat)).isFalse();
        assertThat(nested.deleteQuietly("../outside.jpg")).isFalse();
        assertThat(nested.deleteQuietly("chat/../../outside.jpg")).isFalse();
        assertThat(nested.deleteQuietly("other/20261013/a.jpg")).isFalse();
        // Windows 下为非法路径（InvalidPathException），其他系统下文件不存在：都不能抛出异常
        org.assertj.core.api.Assertions.assertThatCode(() -> nested.deleteQuietly("chat/20261013/a:b*.jpg"))
                .doesNotThrowAnyException();
        // 目录不删
        assertThat(nested.deleteQuietly(chat.substring(0, chat.lastIndexOf('/')))).isFalse();
        assertThat(nested.exists(chat)).isTrue();
        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    void detectsMimeFromHeader() {
        assertThat(FileStorageService.detectMime(JPEG)).isEqualTo("image/jpeg");
        assertThat(FileStorageService.detectMime(PNG)).isEqualTo("image/png");
        assertThat(FileStorageService.detectMime("GIF89a".getBytes())).isNull();
    }

    private static void assertParamError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
    }
}
