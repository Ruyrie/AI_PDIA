package com.heban.pestai.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.heban.pestai.server.admin.entity.AdminUserRow;
import com.heban.pestai.server.admin.mapper.AdminUserMapper;
import com.heban.pestai.server.admin.request.AdminProfileRequest;
import com.heban.pestai.server.admin.vo.AdminMessageVO;
import com.heban.pestai.server.admin.vo.AdminRecognitionVO;
import com.heban.pestai.server.admin.vo.AdminUserDetailVO;
import com.heban.pestai.server.admin.vo.AdminUserRowVO;
import com.heban.pestai.server.admin.vo.TempPasswordVO;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiMessageImage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.entity.User;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.service.AccountService;
import com.heban.pestai.server.service.AiSessionService;
import com.heban.pestai.server.service.FileStorageService;
import com.heban.pestai.server.service.UserAssembler;
import com.heban.pestai.server.service.UserService;
import com.heban.pestai.server.vo.PageVO;

/** 后台用户管理：停用 / 启用 / 强制下线 / 修改资料 / 重置密码 / 彻底删除 / 注销，以及只读查询的组装。 */
class AdminUserServiceTest {

    private static final long USER_ID = 10001L;
    private static final String PHONE = "13800005678";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 9, 0);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    @TempDir
    Path tempDir;

    private AdminUserMapper adminUserMapper;
    private UserMapper userMapper;
    private AiSessionMapper aiSessionMapper;
    private AiMessageMapper aiMessageMapper;
    private AiSessionService aiSessionService;
    private AccountService accountService;
    private AdminLogService logService;
    private FileStorageService files;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        adminUserMapper = mock(AdminUserMapper.class);
        userMapper = mock(UserMapper.class);
        aiSessionMapper = mock(AiSessionMapper.class);
        aiMessageMapper = mock(AiMessageMapper.class);
        aiSessionService = mock(AiSessionService.class);
        accountService = mock(AccountService.class);
        logService = mock(AdminLogService.class);
        files = new FileStorageService(tempDir, "http://47.100.1.2", clock);
        UserService userService = new UserService(userMapper, new UserAssembler(files), files, clock);
        service = new AdminUserService(adminUserMapper, userMapper, aiSessionMapper, aiMessageMapper, aiSessionService,
                accountService, userService, files, logService, ENCODER, clock);
        when(userMapper.selectById(USER_ID)).thenReturn(user(null));
        when(adminUserMapper.selectUserRow(USER_ID)).thenReturn(row());
    }

    @Test
    void listEscapesKeywordMapsStatusAndBuildsFullUrls() {
        // 关键字 50%\_ 转义为 50\%\\\_（LIKE 中按字面匹配）
        when(adminUserMapper.countUsers("50\\%\\\\\\_", 1)).thenReturn(21L);
        when(adminUserMapper.selectUserPage("50\\%\\\\\\_", 1, 0L, 20)).thenReturn(List.of(row()));

        PageVO<AdminUserRowVO> page = service.page(" 50%\\_ ", "DISABLED", 1, 20);
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.hasMore()).isTrue();
        AdminUserRowVO vo = page.list().get(0);
        assertThat(vo.phone()).isEqualTo(PHONE);
        assertThat(vo.status()).isEqualTo("DISABLED");
        assertThat(vo.avatarUrl()).isEqualTo("http://47.100.1.2/files/avatar/20261014/a.jpg");
        assertThat(vo.recognitionCount()).isEqualTo(7);

        assertThat(AdminUserService.parseStatus("")).isNull();
        assertThat(AdminUserService.parseStatus("NORMAL")).isZero();
        assertThatThrownBy(() -> AdminUserService.parseStatus("FROZEN")).isInstanceOf(BizException.class);
        assertThat(service.page(null, null, 3, 20).list()).isEmpty();
    }

    @Test
    void detailAddsSessionCount() {
        when(aiSessionMapper.countWithMessagesByUser(USER_ID)).thenReturn(4L);
        AdminUserDetailVO detail = service.detail(USER_ID);
        assertThat(detail.sessionCount()).isEqualTo(4);
        assertThat(detail.recoveryCodeGenerated()).isTrue();
        assertThat(detail.region()).isEqualTo("江苏");
        when(adminUserMapper.selectUserRow(1L)).thenReturn(null);
        assertParam(() -> service.detail(1L), "用户不存在");
    }

    @Test
    void recognitionsIncludeDeletedAndHideFailedResultFields() {
        Recognition ok = new Recognition();
        ok.setId(1L);
        ok.setMode("PEST");
        ok.setStatus("SUCCESS");
        ok.setTopName("黄瓜霜霉病");
        ok.setConfidence(new BigDecimal("0.920"));
        ok.setImageUrl("rec/20261014/r.jpg");
        ok.setDeleted(1);
        ok.setModel("deepseek-flash");
        ok.setFallback(true);
        Recognition failed = new Recognition();
        failed.setId(2L);
        failed.setStatus("FAILED");
        failed.setTopName("x");
        failed.setDeleted(0);
        when(adminUserMapper.countRecognitions(USER_ID)).thenReturn(2L);
        when(adminUserMapper.selectRecognitionPage(USER_ID, 0, 20)).thenReturn(List.of(ok, failed));

        List<AdminRecognitionVO> list = service.recognitions(USER_ID, 1, 20).list();
        assertThat(list.get(0).deleted()).isTrue();
        assertThat(list.get(0).confidence()).isEqualByComparingTo("0.92");
        assertThat(list.get(0).confidence().toPlainString()).isEqualTo("0.92");
        assertThat(list.get(0).imageUrl()).isEqualTo("http://47.100.1.2/files/rec/20261014/r.jpg");
        assertThat(list.get(1).topName()).isNull();
        assertThat(list.get(1).deleted()).isFalse();
        // 由备用模型给出的结果；旧记录没有模型，按非备用
        assertThat(list.get(0).model()).isEqualTo("deepseek-flash");
        assertThat(list.get(0).fallback()).isTrue();
        assertThat(list.get(1).model()).isNull();
        assertThat(list.get(1).fallback()).isFalse();
    }

    @Test
    void messagesReturnFullImageUrlsInOrder() {
        AiSession session = new AiSession();
        session.setId(5L);
        session.setUserId(USER_ID);
        when(aiSessionMapper.selectById(5L)).thenReturn(session);
        AiMessage question = new AiMessage(5L, "user", "看看这两张", "chat/20261014/a.jpg", NOW);
        question.setId(11L);
        AiMessage answer = new AiMessage(5L, "assistant", "是霜霉病", null, NOW);
        answer.setId(12L);
        answer.setReasoningContent("先看叶背");
        answer.setModel("qwen3.8-max");
        answer.setFallback(true);
        when(aiMessageMapper.selectBySession(5L)).thenReturn(List.of(question, answer));
        AiMessageImage first = new AiMessageImage();
        first.setMessageId(11L);
        first.setImageUrl("chat/20261014/a.jpg");
        AiMessageImage second = new AiMessageImage();
        second.setMessageId(11L);
        second.setImageUrl("chat/20261014/b.png");
        when(aiMessageMapper.selectImagesByMessageIds(List.of(11L))).thenReturn(List.of(first, second));

        List<AdminMessageVO> messages = service.messages(5L);
        assertThat(messages.get(0).imageUrls()).containsExactly("http://47.100.1.2/files/chat/20261014/a.jpg",
                "http://47.100.1.2/files/chat/20261014/b.png");
        assertThat(messages.get(1).imageUrls()).isEmpty();
        assertThat(messages.get(1).reasoningContent()).isEqualTo("先看叶背");
        assertThat(messages.get(1).model()).isEqualTo("qwen3.8-max");
        assertThat(messages.get(1).fallback()).isTrue();
        assertThat(messages.get(0).model()).isNull();
        assertThat(messages.get(0).fallback()).isFalse();
        assertParam(() -> service.messages(6L), "会话不存在");
    }

    @Test
    void disableBumpsTokenVersionEnableDoesNotAndLogoutBumps() {
        service.disable(USER_ID, "发布广告");
        verify(adminUserMapper).updateStatus(USER_ID, User.STATUS_DISABLED, true, NOW);
        verify(logService).log(eq(AdminActions.USER_DISABLE), eq("用户 10001（13800005678）"), eq("停用并强制下线；原因：发布广告"));

        service.enable(USER_ID);
        verify(adminUserMapper).updateStatus(USER_ID, User.STATUS_NORMAL, false, NOW);

        service.forceLogout(USER_ID);
        verify(userMapper).increaseTokenVersion(USER_ID, NOW);
        verify(logService).log(eq(AdminActions.USER_LOGOUT), anyString(), anyString());

        when(userMapper.selectById(1L)).thenReturn(null);
        assertParam(() -> service.disable(1L, null), "用户不存在");
    }

    @Test
    void profileNormalizesCropsKeepsIdentityWhenNullAndDeletesClearedAvatar() {
        String avatar = files.save(JPEG, FileStorageService.AVATAR);
        when(userMapper.selectById(USER_ID)).thenReturn(user(avatar));
        when(userMapper.selectAvatarUrlsInUse(List.of(avatar))).thenReturn(List.of());

        AdminProfileRequest request = new AdminProfileRequest();
        request.setNickname("  老李  ");
        request.setCrops("番茄，黄瓜、番茄 ,");
        request.setClearAvatar(true);
        service.updateProfile(USER_ID, request);

        // identity 未给出 → 不修改；crops 给出 → 规范后更新
        verify(adminUserMapper).updateProfile(USER_ID, "老李", null, false, "番茄,黄瓜", true, true, NOW);
        assertThat(files.exists(avatar)).isFalse();
        verify(logService).log(eq(AdminActions.USER_PROFILE), anyString(), eq("昵称：老李；作物：番茄,黄瓜；清除头像"));

        // 不清除头像时不删文件；identity 为三种之一 → 修改；crops 未给出 → 不修改
        String kept = files.save(JPEG, FileStorageService.AVATAR);
        when(userMapper.selectById(USER_ID)).thenReturn(user(kept));
        request.setClearAvatar(false);
        request.setIdentity("农技人员");
        request.setCrops(null);
        service.updateProfile(USER_ID, request);
        verify(adminUserMapper).updateProfile(USER_ID, "老李", "农技人员", false, null, false, false, NOW);
        assertThat(files.exists(kept)).isTrue();
    }

    @Test
    void emptyIdentityClearsItAndEmptyCropsClearThem() {
        AdminProfileRequest request = new AdminProfileRequest();
        request.setNickname("老李");
        request.setIdentity("");
        request.setCrops("");
        service.updateProfile(USER_ID, request);
        verify(adminUserMapper).updateProfile(USER_ID, "老李", null, true, null, true, false, NOW);
        verify(logService).log(eq(AdminActions.USER_PROFILE), anyString(), eq("昵称：老李；身份：（清除）；作物：（清空）"));
    }

    @Test
    void identityValidationAcceptsEmptyAndThreeIdentitiesOnly() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        AdminProfileRequest request = new AdminProfileRequest();
        request.setNickname("老李");
        for (String ok : new String[]{null, "", "种植户", "家庭园艺", "农技人员"}) {
            request.setIdentity(ok);
            assertThat(validator.validate(request)).as(String.valueOf(ok)).isEmpty();
        }
        for (String bad : new String[]{" ", "果农", "种植户 ", "种植户|家庭园艺"}) {
            request.setIdentity(bad);
            assertThat(validator.validate(request)).as(bad).extracting(v -> v.getMessage())
                    .containsExactly("种植身份只能是种植户、家庭园艺或农技人员");
        }
    }

    @Test
    void resetPasswordReturns12CharTempPasswordAndBumpsVersion() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        TempPasswordVO vo = service.resetPassword(USER_ID);
        assertThat(vo.tempPassword()).hasSize(12).matches("[A-Za-z0-9]+").matches(".*\\d.*").matches(".*[A-Za-z].*");
        verify(userMapper).updatePasswordAndBumpVersion(eq(USER_ID), hash.capture(), eq(NOW));
        assertThat(ENCODER.matches(vo.tempPassword(), hash.getValue())).isTrue();
        // 日志中不出现临时密码
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(logService).log(eq(AdminActions.USER_RESET_PASSWORD), anyString(), detail.capture());
        assertThat(detail.getValue()).doesNotContain(vo.tempPassword());
    }

    @Test
    void deleteRecognitionRemovesRowAndPhotoAndIsIdempotent() {
        String photo = files.save(JPEG, FileStorageService.REC);
        Recognition record = new Recognition();
        record.setId(9L);
        record.setUserId(USER_ID);
        record.setImageUrl(photo);
        when(adminUserMapper.selectRecognitionFile(9L)).thenReturn(record);

        service.deleteRecognition(9L);
        verify(adminUserMapper).deleteRecognition(9L);
        assertThat(files.exists(photo)).isFalse();
        verify(logService).log(eq(AdminActions.RECOGNITION_DELETE), eq("识别记录 9"), anyString());

        service.deleteRecognition(10L);
        verify(adminUserMapper, never()).deleteRecognition(10L);
    }

    @Test
    void deleteSessionReusesAppDeletionAsOwner() {
        AiSession session = new AiSession();
        session.setId(5L);
        session.setUserId(USER_ID);
        session.setTitle("追问：黄瓜霜霉病");
        when(aiSessionMapper.selectById(5L)).thenReturn(session);
        service.deleteSession(5L);
        verify(aiSessionService).delete(USER_ID, 5L);
        verify(logService).log(eq(AdminActions.SESSION_DELETE), eq("会话 5"), anyString());

        service.deleteSession(6L);
        verify(aiSessionService, never()).delete(anyLong(), eq(6L));
    }

    @Test
    void deactivateRequiresFullPhone() {
        assertParam(() -> service.deactivate(USER_ID, "5678"), "手机号不一致，请输入该用户的完整手机号");
        assertParam(() -> service.deactivate(USER_ID, null), "手机号不一致，请输入该用户的完整手机号");
        verifyNoInteractions(accountService);

        service.deactivate(USER_ID, " 13800005678 ");
        verify(accountService).deactivateByAdmin(USER_ID);
        verify(logService).log(eq(AdminActions.USER_DEACTIVATE), anyString(), anyString());
    }

    @Test
    void likeEscaping() {
        assertThat(AdminUserService.escapeLike("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
    }

    private static User user(String avatar) {
        User u = new User();
        u.setId(USER_ID);
        u.setPhone(PHONE);
        u.setNickname("老李种菜");
        u.setAvatarUrl(avatar);
        u.setStatus(User.STATUS_NORMAL);
        u.setTokenVersion(0);
        return u;
    }

    private static AdminUserRow row() {
        AdminUserRow r = new AdminUserRow();
        r.setId(USER_ID);
        r.setPhone(PHONE);
        r.setNickname("老李种菜");
        r.setAvatarUrl("avatar/20261014/a.jpg");
        r.setRegion("江苏");
        r.setIdentity("种植户");
        r.setCrops("番茄,黄瓜");
        r.setStatus(User.STATUS_DISABLED);
        r.setRecoveryCodeGenerated(true);
        r.setRecognitionCount(7L);
        r.setAiQuestionCount(3L);
        return r;
    }

    private static void assertParam(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }
}
