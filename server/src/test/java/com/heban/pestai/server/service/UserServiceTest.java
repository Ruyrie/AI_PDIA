package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.request.ProfileRequest;
import com.heban.pestai.server.vo.UserVO;

/** 保存资料时更换头像：换成新头像后删除旧头像文件（提交之后），保留原头像或头像未变时不删除。 */
class UserServiceTest {

    private static final long USER_ID = 10001L;
    private static final String BASE = "http://10.0.2.2:8080";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 9, 30, 0);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};

    @TempDir
    Path tempDir;

    private UserMapper userMapper;
    private FileStorageService files;
    private UserService service;
    /** 数据库中当前的头像（updateProfile 时按 Mapper 的规则更新：avatarUrl 为 null 保留原值） */
    private String storedAvatar;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        userMapper = mock(UserMapper.class);
        files = new FileStorageService(tempDir, BASE, clock);
        service = new UserService(userMapper, new UserAssembler(files), files, clock);
        when(userMapper.selectById(USER_ID)).thenAnswer(inv -> user(storedAvatar));
        when(userMapper.updateProfile(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getAvatarUrl() != null) {
                storedAvatar = u.getAvatarUrl();
            }
            return 1;
        });
    }

    @Test
    void newAvatarReplacesOldOneAndOldFileIsDeleted() {
        String old = files.save(JPEG, FileStorageService.AVATAR);
        String fresh = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = old;

        UserVO vo = service.updateProfile(USER_ID, request(BASE + "/files/" + fresh));

        assertThat(vo.avatarUrl()).isEqualTo(BASE + "/files/" + fresh);
        assertThat(files.exists(old)).isFalse();
        assertThat(files.exists(fresh)).isTrue();
        // 删除前核对旧头像是否仍被其他账号引用
        verify(userMapper).selectAvatarUrlsInUse(List.of(old));
    }

    @Test
    void oldAvatarIsDeletedOnlyAfterCommit() {
        String old = files.save(JPEG, FileStorageService.AVATAR);
        String fresh = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = old;

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateProfile(USER_ID, request(BASE + "/files/" + fresh));
            // 方法返回时事务尚未提交：旧头像还在
            assertThat(files.exists(old)).isTrue();
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            // 回滚：只会触发 afterCompletion，旧头像保留
            synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(files.exists(old)).isTrue();

            // 提交：删除旧头像，新头像保留
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            assertThat(files.exists(old)).isFalse();
            assertThat(files.exists(fresh)).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sameAvatarOrNoAvatarInRequestKeepsTheFile() {
        String current = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = current;

        // 头像未变（App 原样传回当前地址）
        service.updateProfile(USER_ID, request(BASE + "/files/" + current));
        // avatar_url 为空：保留原头像
        service.updateProfile(USER_ID, request(null));
        service.updateProfile(USER_ID, request("  "));

        assertThat(files.exists(current)).isTrue();
        assertThat(storedAvatar).isEqualTo(current);
        verify(userMapper, never()).selectAvatarUrlsInUse(any());
    }

    @Test
    void firstAvatarDeletesNothing() {
        String fresh = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = null;

        UserVO vo = service.updateProfile(USER_ID, request(BASE + "/files/" + fresh));

        assertThat(vo.avatarUrl()).isEqualTo(BASE + "/files/" + fresh);
        assertThat(files.exists(fresh)).isTrue();
        verify(userMapper, never()).selectAvatarUrlsInUse(any());
    }

    @Test
    void oldAvatarStillUsedByAnotherAccountOrOutsideAvatarDirIsKept() {
        String shared = files.save(JPEG, FileStorageService.AVATAR);
        String fresh = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = shared;
        when(userMapper.selectAvatarUrlsInUse(List.of(shared))).thenReturn(List.of(shared));

        service.updateProfile(USER_ID, request(BASE + "/files/" + fresh));
        assertThat(files.exists(shared)).isTrue();

        // 旧值不是 avatar/ 下的相对路径（如早期写入的其他类别图片）：不删除，也不查询
        String rec = files.save(JPEG, FileStorageService.REC);
        storedAvatar = rec;
        String another = files.save(JPEG, FileStorageService.AVATAR);
        service.updateProfile(USER_ID, request(BASE + "/files/" + another));
        assertThat(files.exists(rec)).isTrue();
        verify(userMapper, never()).selectAvatarUrlsInUse(List.of(rec));
    }

    @Test
    void referenceCheckFailureKeepsOldAvatarAndStillSavesProfile() {
        String old = files.save(JPEG, FileStorageService.AVATAR);
        String fresh = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = old;
        when(userMapper.selectAvatarUrlsInUse(any())).thenThrow(new IllegalStateException("数据库不可用"));

        UserVO vo = service.updateProfile(USER_ID, request(BASE + "/files/" + fresh));

        assertThat(vo.avatarUrl()).isEqualTo(BASE + "/files/" + fresh);
        assertThat(files.exists(old)).isTrue();
    }

    @Test
    void invalidAvatarOrMissingUserDeletesNothing() {
        String old = files.save(JPEG, FileStorageService.AVATAR);
        storedAvatar = old;

        assertThatThrownBy(() -> service.updateProfile(USER_ID, request("https://evil.example.com/a.jpg")))
                .isInstanceOf(BizException.class)
                .hasMessage("头像地址无效，请重新上传头像");
        verify(userMapper, never()).updateProfile(any());

        String fresh = files.save(JPEG, FileStorageService.AVATAR);
        // 用户已被并发注销：更新 0 行
        doReturn(0).when(userMapper).updateProfile(any());
        assertThatThrownBy(() -> service.updateProfile(USER_ID, request(BASE + "/files/" + fresh)))
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        assertThat(files.exists(old)).isTrue();
        verify(userMapper, never()).selectAvatarUrlsInUse(any());
    }

    @Test
    void profileFieldsAreNormalized() {
        ProfileRequest request = request(null);
        request.setNickname("  老李种菜 ");
        request.setRegion(null);
        request.setCrops(" 番茄，黄瓜、番茄 ");

        service.updateProfile(USER_ID, request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateProfile(saved.capture());
        assertThat(saved.getValue().getNickname()).isEqualTo("老李种菜");
        assertThat(saved.getValue().getRegion()).isEmpty();
        assertThat(saved.getValue().getCrops()).isEqualTo("番茄,黄瓜");
        assertThat(saved.getValue().getAvatarUrl()).isNull();
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    private static ProfileRequest request(String avatarUrl) {
        ProfileRequest r = new ProfileRequest();
        r.setNickname("老李种菜");
        r.setAvatarUrl(avatarUrl);
        r.setRegion("江苏省 南京市 江宁区");
        r.setIdentity("种植户");
        r.setCrops("番茄,黄瓜");
        return r;
    }

    private static User user(String avatar) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone("13800005678");
        u.setNickname("老李种菜");
        u.setAvatarUrl(avatar);
        u.setRegion("江苏省 南京市 江宁区");
        u.setIdentity("种植户");
        u.setStatus(User.STATUS_NORMAL);
        return u;
    }
}
