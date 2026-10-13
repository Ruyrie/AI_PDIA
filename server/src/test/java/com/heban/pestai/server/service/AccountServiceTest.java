package com.heban.pestai.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.SmsScene;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.ResetTicketMapper;
import com.heban.pestai.server.mapper.SmsCodeMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.security.JwtTokenProvider;
import com.heban.pestai.server.vo.LoginVO;

class AccountServiceTest {

    private static final long USER_ID = 10001L;
    private static final String PHONE = "13800005678";
    private static final String NEW_PHONE = "13900001111";
    private static final String CODE = "048215";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0, 0);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};

    @TempDir
    Path tempDir;

    private UserMapper userMapper;
    private RecognitionMapper recognitionMapper;
    private AiSessionMapper aiSessionMapper;
    private AiMessageMapper aiMessageMapper;
    private ResetTicketMapper resetTicketMapper;
    private SmsCodeMapper smsCodeMapper;
    private SmsService smsService;
    private FileStorageService files;
    private JwtTokenProvider tokens;
    private AccountService account;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        userMapper = mock(UserMapper.class);
        recognitionMapper = mock(RecognitionMapper.class);
        aiSessionMapper = mock(AiSessionMapper.class);
        aiMessageMapper = mock(AiMessageMapper.class);
        resetTicketMapper = mock(ResetTicketMapper.class);
        smsCodeMapper = mock(SmsCodeMapper.class);
        smsService = mock(SmsService.class);
        files = new FileStorageService(tempDir, "http://10.0.2.2:8080", clock);
        tokens = new JwtTokenProvider("unit-test-secret-0123456789-abcdefghijklmnop", Duration.ofDays(30), clock);
        account = new AccountService(userMapper, recognitionMapper, aiSessionMapper, aiMessageMapper, resetTicketMapper,
                smsCodeMapper, smsService, files, tokens, new UserAssembler(files), clock);
        when(userMapper.selectById(USER_ID)).thenReturn(user(PHONE, 2, null));
    }

    // ------------------------------------------------------------------ 更换手机号

    @Test
    void changePhoneCodeIsSentToNewPhone() {
        account.sendChangePhoneCode(USER_ID, NEW_PHONE);

        verify(userMapper).selectByPhone(NEW_PHONE);
        verify(smsService).send(NEW_PHONE, SmsScene.CHANGE_PHONE);
    }

    @Test
    void changePhoneCodeRejectsSamePhoneRegisteredPhoneAndPropagatesRateLimit() {
        assertBiz(() -> account.sendChangePhoneCode(USER_ID, PHONE), ErrorCode.PARAM_ERROR, AccountService.SAME_PHONE);

        when(userMapper.selectByPhone(NEW_PHONE)).thenReturn(user(NEW_PHONE, 0, null));
        assertBiz(() -> account.sendChangePhoneCode(USER_ID, NEW_PHONE),
                ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
        verify(smsService, never()).send(anyString(), any());

        when(userMapper.selectByPhone(NEW_PHONE)).thenReturn(null);
        doThrow(new BizException(ErrorCode.TOO_FREQUENT)).when(smsService).send(NEW_PHONE, SmsScene.CHANGE_PHONE);
        assertBiz(() -> account.sendChangePhoneCode(USER_ID, NEW_PHONE), ErrorCode.TOO_FREQUENT, null);
    }

    @Test
    void missingOrDisabledUserIs40101() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertBiz(() -> account.sendChangePhoneCode(USER_ID, NEW_PHONE), ErrorCode.TOKEN_INVALID, null);
        assertBiz(() -> account.sendDeactivateCode(USER_ID), ErrorCode.TOKEN_INVALID, null);

        User disabled = user(PHONE, 2, null);
        disabled.setStatus(User.STATUS_DISABLED);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);
        assertBiz(() -> account.changePhone(USER_ID, NEW_PHONE, CODE), ErrorCode.TOKEN_INVALID, null);
        assertBiz(() -> account.deactivate(USER_ID, CODE), ErrorCode.TOKEN_INVALID, null);
        verifyNoInteractions(smsService);
    }

    @Test
    void changePhoneUpdatesPhoneBumpsVersionAndIssuesTokenForNewVersion() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(PHONE, 2, null), user(NEW_PHONE, 3, null));
        when(userMapper.updatePhoneAndBumpVersion(USER_ID, NEW_PHONE, NOW)).thenReturn(1);

        LoginVO vo = account.changePhone(USER_ID, NEW_PHONE, CODE);

        InOrder order = inOrder(smsService, userMapper);
        order.verify(smsService).verifyAndConsume(NEW_PHONE, SmsScene.CHANGE_PHONE, CODE);
        order.verify(userMapper).selectByPhone(NEW_PHONE);
        order.verify(userMapper).updatePhoneAndBumpVersion(USER_ID, NEW_PHONE, NOW);
        assertThat(vo.isNew()).isFalse();
        assertThat(vo.user().phone()).isEqualTo(NEW_PHONE);
        assertThat(vo.user().id()).isEqualTo(USER_ID);
        JwtTokenProvider.TokenClaims claims = tokens.parse(vo.token());
        assertThat(claims.userId()).isEqualTo(USER_ID);
        // 新令牌对应加 1 之后的版本号，旧令牌（版本 2）随之失效
        assertThat(claims.tokenVersion()).isEqualTo(3);
        // 换绑不是登录，不更新 last_login_at
        verify(userMapper, never()).updateLastLogin(anyLong(), any());
    }

    @Test
    void changePhoneRejectsSamePhoneBeforeConsumingCode() {
        assertBiz(() -> account.changePhone(USER_ID, PHONE, CODE), ErrorCode.PARAM_ERROR, AccountService.SAME_PHONE);
        verifyNoInteractions(smsService);
        verify(userMapper, never()).updatePhoneAndBumpVersion(anyLong(), anyString(), any());
    }

    @Test
    void changePhoneWithWrongCodeIs40103AndChangesNothing() {
        doThrow(new BizException(ErrorCode.SMS_CODE_INVALID)).when(smsService)
                .verifyAndConsume(NEW_PHONE, SmsScene.CHANGE_PHONE, "000000");

        assertBiz(() -> account.changePhone(USER_ID, NEW_PHONE, "000000"), ErrorCode.SMS_CODE_INVALID, null);
        verify(userMapper, never()).updatePhoneAndBumpVersion(anyLong(), anyString(), any());
    }

    @Test
    void changePhoneToRegisteredPhoneIs40901() {
        when(userMapper.selectByPhone(NEW_PHONE)).thenReturn(user(NEW_PHONE, 0, null));

        assertBiz(() -> account.changePhone(USER_ID, NEW_PHONE, CODE),
                ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
        verify(userMapper, never()).updatePhoneAndBumpVersion(anyLong(), anyString(), any());
    }

    @Test
    void changePhoneLosingRaceOnUniqueIndexIs40901() {
        when(userMapper.updatePhoneAndBumpVersion(USER_ID, NEW_PHONE, NOW))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_phone'"));

        assertBiz(() -> account.changePhone(USER_ID, NEW_PHONE, CODE),
                ErrorCode.PHONE_REGISTERED, AccountService.PHONE_TAKEN);
    }

    // ------------------------------------------------------------------ 注销账号

    @Test
    void deactivateCodeIsSentToCurrentPhone() {
        account.sendDeactivateCode(USER_ID);
        verify(smsService).send(PHONE, SmsScene.DEACTIVATE);
    }

    @Test
    void deactivateDeletesRowsInOrderThenFiles() {
        String avatar = files.save(JPEG, FileStorageService.AVATAR);
        String rec = files.save(JPEG, FileStorageService.REC);
        String recLogicallyDeleted = files.save(JPEG, FileStorageService.REC);
        String chat = files.save(JPEG, FileStorageService.CHAT);
        String someoneElse = files.save(JPEG, FileStorageService.REC);
        when(userMapper.selectById(USER_ID)).thenReturn(user(PHONE, 2, avatar));
        when(recognitionMapper.selectAllImageUrlsByUser(USER_ID)).thenReturn(List.of(rec, recLogicallyDeleted));
        // 文件已不在磁盘上：尽力删除，不报错
        when(aiMessageMapper.selectImageUrlsByUser(USER_ID)).thenReturn(List.of(chat, "chat/20261001/missing.jpg"));
        when(userMapper.deleteById(USER_ID)).thenReturn(1);

        account.deactivate(USER_ID, CODE);

        InOrder order = inOrder(smsService, recognitionMapper, aiMessageMapper, aiSessionMapper, resetTicketMapper,
                smsCodeMapper, userMapper);
        order.verify(smsService).verifyAndConsume(PHONE, SmsScene.DEACTIVATE, CODE);
        order.verify(recognitionMapper).selectAllImageUrlsByUser(USER_ID);
        order.verify(aiMessageMapper).selectImageUrlsByUser(USER_ID);
        order.verify(aiMessageMapper).deleteByUser(USER_ID);
        order.verify(aiSessionMapper).deleteByUser(USER_ID);
        order.verify(recognitionMapper).deleteAllByUser(USER_ID);
        order.verify(resetTicketMapper).deleteByUser(USER_ID);
        order.verify(smsCodeMapper).deleteByPhone(PHONE);
        order.verify(userMapper).deleteById(USER_ID);
        order.verifyNoMoreInteractions();

        assertThat(files.exists(avatar)).isFalse();
        assertThat(files.exists(rec)).isFalse();
        assertThat(files.exists(recLogicallyDeleted)).isFalse();
        assertThat(files.exists(chat)).isFalse();
        assertThat(files.exists(someoneElse)).isTrue();
    }

    @Test
    void filesAreDeletedOnlyAfterCommit() {
        String avatar = files.save(JPEG, FileStorageService.AVATAR);
        String rec = files.save(JPEG, FileStorageService.REC);
        when(userMapper.selectById(USER_ID)).thenReturn(user(PHONE, 2, avatar));
        when(recognitionMapper.selectAllImageUrlsByUser(USER_ID)).thenReturn(List.of(rec));
        when(userMapper.deleteById(USER_ID)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            account.deactivate(USER_ID, CODE);
            // 方法返回时事务尚未提交：文件还在
            assertThat(files.exists(avatar)).isTrue();
            assertThat(files.exists(rec)).isTrue();
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            // 回滚：只会触发 afterCompletion，文件保留
            synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(files.exists(avatar)).isTrue();

            // 提交：删除文件
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            assertThat(files.exists(avatar)).isFalse();
            assertThat(files.exists(rec)).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deactivateWithWrongCodeDeletesNothing() {
        String avatar = files.save(JPEG, FileStorageService.AVATAR);
        when(userMapper.selectById(USER_ID)).thenReturn(user(PHONE, 2, avatar));
        doThrow(new BizException(ErrorCode.SMS_CODE_INVALID)).when(smsService)
                .verifyAndConsume(PHONE, SmsScene.DEACTIVATE, "000000");

        assertBiz(() -> account.deactivate(USER_ID, "000000"), ErrorCode.SMS_CODE_INVALID, null);

        verifyNoInteractions(recognitionMapper, aiSessionMapper, aiMessageMapper, resetTicketMapper, smsCodeMapper);
        verify(userMapper, never()).deleteById(anyLong());
        assertThat(files.exists(avatar)).isTrue();
    }

    @Test
    void deactivateWhenUserAlreadyDeletedConcurrentlyIs40101AndKeepsFiles() {
        String avatar = files.save(JPEG, FileStorageService.AVATAR);
        when(userMapper.selectById(USER_ID)).thenReturn(user(PHONE, 2, avatar));
        when(userMapper.deleteById(USER_ID)).thenReturn(0);

        assertBiz(() -> account.deactivate(USER_ID, CODE), ErrorCode.TOKEN_INVALID, null);
        assertThat(files.exists(avatar)).isTrue();
    }

    private static void assertBiz(ThrowingCallable call, ErrorCode code, String message) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(code);
                    if (message != null) {
                        assertThat(e.getMessage()).isEqualTo(message);
                    }
                });
    }

    private static User user(String phone, int tokenVersion, String avatar) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone(phone);
        u.setNickname("老李种菜");
        u.setAvatarUrl(avatar);
        u.setRegion("江苏省 南京市 江宁区");
        u.setIdentity("种植户");
        u.setStatus(User.STATUS_NORMAL);
        u.setTokenVersion(tokenVersion);
        return u;
    }
}
