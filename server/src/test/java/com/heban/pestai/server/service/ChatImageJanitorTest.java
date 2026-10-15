package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.UserMapper;

/**
 * 定期清理未发送的聊天图片与未保存的头像：只清理前天及更早的日期目录，被消息 / 账号引用的图片保留
 * （Mapper Mock，文件写在临时目录）。
 */
class ChatImageJanitorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 今天 2026-10-14：20261014、20261013 保留，20261012 及更早清理 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 4, 30, 0);

    @TempDir
    Path root;

    private AiMessageMapper messages;
    private UserMapper users;
    private ChatImageJanitor janitor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        messages = mock(AiMessageMapper.class);
        users = mock(UserMapper.class);
        janitor = new ChatImageJanitor(messages, users, new FileStorageService(root, "http://10.0.2.2:8080", clock), clock);
    }

    @Test
    void unreferencedImagesInOldDirectoriesAreDeletedAndReferencedOnesKept() throws Exception {
        Path today = writeFile("chat/20261014/today.jpg");
        Path yesterday = writeFile("chat/20261013/yesterday.jpg");
        Path sent = writeFile("chat/20261012/sent.jpg");
        Path removed = writeFile("chat/20261012/removed.png");
        Path tmp = writeFile("chat/20261012/.upload-123.tmp");
        Path other = writeFile("chat/20261012/readme.txt");
        Path oldOrphan = writeFile("chat/20260901/orphan.jpg");
        Path notADate = writeFile("chat/backup/x.jpg");
        Path badDate = writeFile("chat/20261399/x.jpg");
        Path rec = writeFile("rec/20261001/r.jpg");
        when(messages.selectImageUrlsInUse(any())).thenReturn(List.of("chat/20261012/sent.jpg"));

        ChatImageJanitor.Result result = janitor.clean();

        assertThat(today).exists();
        assertThat(yesterday).exists();
        assertThat(sent).exists();
        assertThat(removed).doesNotExist();
        assertThat(tmp).doesNotExist();
        assertThat(other).exists();
        assertThat(oldOrphan).doesNotExist();
        // 删空的日期目录一并删除；不是日期的目录、非法日期、其他类别都不动
        assertThat(oldOrphan.getParent()).doesNotExist();
        assertThat(sent.getParent()).exists();
        assertThat(notADate).exists();
        assertThat(badDate).exists();
        assertThat(rec).exists();

        assertThat(result.directories).isEqualTo(2);
        assertThat(result.scanned).isEqualTo(3);
        assertThat(result.deletedImages).isEqualTo(2);
        assertThat(result.deletedTemps).isEqualTo(1);
        assertThat(result.removedDirectories).isEqualTo(1);

        // 核对时用的相对路径与入库时一致（chat/{yyyyMMdd}/{文件名}），只核对旧目录中的图片
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> checked = ArgumentCaptor.forClass(Collection.class);
        verify(messages, times(2)).selectImageUrlsInUse(checked.capture());
        List<String> all = new ArrayList<>();
        checked.getAllValues().forEach(all::addAll);
        assertThat(all).containsExactlyInAnyOrder(
                "chat/20260901/orphan.jpg", "chat/20261012/removed.png", "chat/20261012/sent.jpg");
    }

    @Test
    void imagesAreCheckedInBatchesOfFiveHundred() throws Exception {
        for (int i = 0; i < ChatImageJanitor.BATCH_SIZE + 3; i++) {
            writeFile(String.format("chat/20261001/%04d.jpg", i));
        }
        // 第 1 张仍被引用
        when(messages.selectImageUrlsInUse(any())).thenReturn(List.of("chat/20261001/0000.jpg"));

        ChatImageJanitor.Result result = janitor.clean();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> checked = ArgumentCaptor.forClass(Collection.class);
        verify(messages, times(2)).selectImageUrlsInUse(checked.capture());
        assertThat(checked.getAllValues().get(0)).hasSize(ChatImageJanitor.BATCH_SIZE);
        assertThat(checked.getAllValues().get(1)).hasSize(3);
        assertThat(result.scanned).isEqualTo(ChatImageJanitor.BATCH_SIZE + 3);
        assertThat(result.deletedImages).isEqualTo(ChatImageJanitor.BATCH_SIZE + 2);
        assertThat(root.resolve("chat/20261001/0000.jpg")).exists();
        assertThat(result.removedDirectories).isZero();
    }

    @Test
    void databaseFailureDeletesNoImagesAndNeverThrows() throws Exception {
        Path a = writeFile("chat/20261001/a.jpg");
        Path b = writeFile("chat/20261002/b.jpg");
        when(messages.selectImageUrlsInUse(any())).thenThrow(new IllegalStateException("数据库不可用"));

        ChatImageJanitor.Result[] result = new ChatImageJanitor.Result[1];
        assertThatCode(() -> result[0] = janitor.clean()).doesNotThrowAnyException();
        assertThatCode(janitor::cleanOrphanImages).doesNotThrowAnyException();

        assertThat(a).exists();
        assertThat(b).exists();
        assertThat(result[0].deletedImages).isZero();
    }

    @Test
    void missingChatDirectoryOrOnlyRecentDirectoriesDoNothing() throws Exception {
        assertThat(janitor.clean().directories).isZero();

        writeFile("chat/20261014/a.jpg");
        writeFile("chat/20261013/b.jpg");
        ChatImageJanitor.Result result = janitor.clean();
        assertThat(result.directories).isZero();
        verify(messages, never()).selectImageUrlsInUse(any());

        // 旧目录中只有临时文件：删除临时文件后目录为空，一并删除
        Path tmp = writeFile("chat/20261010/.upload-9.tmp");
        result = janitor.clean();
        assertThat(tmp).doesNotExist();
        assertThat(tmp.getParent()).doesNotExist();
        assertThat(result.deletedTemps).isEqualTo(1);
        verify(messages, never()).selectImageUrlsInUse(any());
    }

    @Test
    void unsavedAvatarsInOldDirectoriesAreDeletedAndAvatarsInUseKept() throws Exception {
        Path today = writeFile("avatar/20261014/today.jpg");
        Path yesterday = writeFile("avatar/20261013/yesterday.png");
        Path saved = writeFile("avatar/20261012/saved.jpg");
        Path unsaved = writeFile("avatar/20261012/unsaved.jpg");
        Path tmp = writeFile("avatar/20261012/.upload-7.tmp");
        Path oldOrphan = writeFile("avatar/20260920/orphan.png");
        Path chat = writeFile("chat/20261001/c.jpg");
        when(users.selectAvatarUrlsInUse(any())).thenReturn(List.of("avatar/20261012/saved.jpg"));

        ChatImageJanitor.Result result = janitor.cleanAvatars();

        assertThat(today).exists();
        assertThat(yesterday).exists();
        assertThat(saved).exists();
        assertThat(unsaved).doesNotExist();
        assertThat(tmp).doesNotExist();
        assertThat(oldOrphan).doesNotExist();
        assertThat(oldOrphan.getParent()).doesNotExist();
        // 只清理头像目录，聊天图片不核对、不删除
        assertThat(chat).exists();
        verify(messages, never()).selectImageUrlsInUse(any());

        assertThat(result.directories).isEqualTo(2);
        assertThat(result.scanned).isEqualTo(3);
        assertThat(result.deletedImages).isEqualTo(2);
        assertThat(result.deletedTemps).isEqualTo(1);
        assertThat(result.removedDirectories).isEqualTo(1);

        // 核对用的相对路径与 t_user.avatar_url 入库时一致：avatar/{yyyyMMdd}/{文件名}
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> checked = ArgumentCaptor.forClass(Collection.class);
        verify(users, times(2)).selectAvatarUrlsInUse(checked.capture());
        List<String> all = new ArrayList<>();
        checked.getAllValues().forEach(all::addAll);
        assertThat(all).containsExactlyInAnyOrder(
                "avatar/20260920/orphan.png", "avatar/20261012/saved.jpg", "avatar/20261012/unsaved.jpg");
    }

    @Test
    void scheduledRunCleansChatThenAvatarsAndAvatarCheckFailureKeepsAvatars() throws Exception {
        Path chat = writeFile("chat/20261001/c.jpg");
        Path avatar = writeFile("avatar/20261001/a.jpg");
        when(messages.selectImageUrlsInUse(any())).thenReturn(List.of());
        when(users.selectAvatarUrlsInUse(any())).thenThrow(new IllegalStateException("数据库不可用"));

        assertThatCode(janitor::cleanOrphanImages).doesNotThrowAnyException();

        assertThat(chat).doesNotExist();
        assertThat(avatar).exists();
        InOrder order = inOrder(messages, users);
        order.verify(messages).selectImageUrlsInUse(any());
        order.verify(users).selectAvatarUrlsInUse(any());
    }

    private Path writeFile(String relative) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});
        return path;
    }
}
