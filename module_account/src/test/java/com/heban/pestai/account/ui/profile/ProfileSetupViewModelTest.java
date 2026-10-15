package com.heban.pestai.account.ui.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.data.request.ProfileRequest;
import com.heban.pestai.account.testutil.FakeAuthApi;
import com.heban.pestai.account.testutil.RxTestRule;
import com.heban.pestai.account.testutil.TestEnv;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class ProfileSetupViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private ProfileSetupViewModel vm;
    private List<String> toasts;
    private List<LoginEvent> done;

    @Before
    public void setUp() {
        env = new TestEnv();
        // 注册后本地缓存的默认资料
        UserVO cached = FakeAuthApi.user("农友5678");
        cached.setRegion("");
        cached.setIdentity(null);
        cached.setCrops("小麦,番茄");
        env.session.saveUser(cached);

        vm = new ProfileSetupViewModel(env.repository);
        toasts = TestEnv.record(vm.getToast());
        done = TestEnv.record(vm.getDone());
        TestEnv.record(vm.getCropTitle());
        TestEnv.record(vm.getAvatarImage());
        TestEnv.record(vm.getCanSave());
        vm.init();
    }

    @Test
    public void init_prefillsFromCachedUser() {
        assertEquals("农友5678", vm.getNickname().getValue());
        assertEquals("种植户", vm.getIdentity().getValue());   // 为空时默认种植户
        assertEquals(2, vm.getSelectedCrops().getValue().size());
        assertEquals("你主要种什么？（已选 2 项，可多选）", vm.getCropTitle().getValue());
    }

    @Test
    public void toggleCrop_updatesTitleAndJoinsInCandidateOrder() {
        vm.toggleCrop("黄瓜");
        assertEquals("你主要种什么？（已选 3 项，可多选）", vm.getCropTitle().getValue());
        assertEquals("番茄,黄瓜,小麦", vm.buildRequest().getCrops());
        vm.toggleCrop("番茄");
        assertEquals("黄瓜,小麦", vm.buildRequest().getCrops());
        assertTrue(vm.isCropSelected("黄瓜"));
    }

    @Test
    public void save_emptyNickname_showsToast() {
        vm.getNickname().setValue("  ");
        vm.save();
        assertEquals(AccountTexts.NICKNAME_EMPTY, toasts.get(toasts.size() - 1));
        assertEquals(0, env.api.updateProfileCount);
    }

    @Test
    public void save_success_sendsTrimmedFormAndFinishes() {
        vm.getNickname().setValue(" 老李种菜 ");
        vm.selectIdentity("家庭园艺");
        vm.save();
        ProfileRequest request = env.api.lastProfile;
        assertEquals("老李种菜", request.getNickname());
        assertEquals("家庭园艺", request.getIdentity());
        assertEquals("番茄,小麦", request.getCrops());
        assertNull(request.getAvatarUrl());   // 未上传头像
        // 保存成功后先进入 A12 引导模式（保存账号恢复码），不再直接进入首页
        assertEquals(Collections.singletonList(LoginEvent.GO_RECOVERY_GUIDE), done);
        assertTrue(env.userJson().contains("老李种菜"));   // 返回的 UserVO 覆盖 user_json
        assertFalse(env.gson.toJson(request).contains("region"));   // 不再提交所在地区
    }

    @Test
    public void uploadAvatar_successUsesNetworkUrl() {
        UploadVO upload = new UploadVO();
        upload.setUrl("http://10.0.2.2:8080/files/avatar/20261013/a.jpg");
        // 压缩依赖 Android 图片解码，这里用跳过压缩的仓库直接验证 ViewModel 对上传结果的处理
        ProfileSetupViewModel uploading = new ProfileSetupViewModel(new FakeUploadRepository(env, Single.just(upload)));
        TestEnv.record(uploading.getAvatarImage());
        uploading.init();
        uploading.uploadAvatar("content://media/picker/1");
        assertEquals(upload.getUrl(), uploading.getAvatarUrl().getValue());
        assertEquals(upload.getUrl(), uploading.getAvatarImage().getValue());
        assertEquals(upload.getUrl(), uploading.buildRequest().getAvatarUrl());
    }

    @Test
    public void uploadAvatar_failureKeepsLocalPreview() {
        ProfileSetupViewModel uploading = new ProfileSetupViewModel(
                new FakeUploadRepository(env, Single.error(new IOException("offline"))));
        List<String> uploadToasts = TestEnv.record(uploading.getToast());
        TestEnv.record(uploading.getAvatarImage());
        uploading.init();
        uploading.uploadAvatar("content://media/picker/1");
        assertEquals("content://media/picker/1", uploading.getAvatarImage().getValue());
        assertNull(uploading.getAvatarUrl().getValue());
        assertEquals(AccountTexts.AVATAR_UPLOAD_FAILED, uploadToasts.get(0));
        assertEquals(Boolean.FALSE, uploading.getAvatarUploading().getValue());
    }

    @Test
    public void save_failure_staysOnPage() {
        env.api.updateProfileResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        vm.save();
        assertTrue(done.isEmpty());
        assertEquals("服务器开小差了，请稍后再试", toasts.get(toasts.size() - 1));
    }

    @Test
    public void skip_goesToRecoveryGuideWithoutRequest() {
        // 「跳过」与返回键（页面同样调用 skip()）
        vm.skip();
        assertEquals(Collections.singletonList(LoginEvent.GO_RECOVERY_GUIDE), done);
        assertEquals(0, env.api.updateProfileCount);
    }

    @Test
    public void setupMode_backGoesToRecoveryGuideWithoutUserMeOrEditEvents() {
        List<ProfileEditEvent> editEvents = TestEnv.record(vm.getEditEvent());
        assertFalse(vm.isEditMode());
        assertEquals(Boolean.FALSE, vm.getEditMode().getValue());
        assertEquals(0, env.api.meCount);   // 完善资料模式不请求 user/me

        vm.getNickname().setValue("老李");   // 有修改也不确认：返回等同「跳过」
        vm.onBack();

        assertEquals(Collections.singletonList(LoginEvent.GO_RECOVERY_GUIDE), done);
        assertTrue(editEvents.isEmpty());
        assertEquals(0, env.api.updateProfileCount);
    }

    @Test
    public void setupMode_saveSuccess_goesToRecoveryGuideOnly() {
        List<ProfileEditEvent> editEvents = TestEnv.record(vm.getEditEvent());
        vm.save();
        assertEquals(Collections.singletonList(LoginEvent.GO_RECOVERY_GUIDE), done);
        assertTrue(editEvents.isEmpty());
    }

    @Test
    public void leaveEvent_emittedOnlyOnce() {
        vm.skip();
        vm.skip();          // 快速连按返回键
        vm.save();          // 已离开：不再保存
        assertEquals(1, done.size());
        assertEquals(0, env.api.updateProfileCount);
    }

    /** 跳过图片压缩，直接返回给定上传结果的仓库 */
    private static final class FakeUploadRepository extends AccountRepository {
        private final Single<UploadVO> result;

        FakeUploadRepository(TestEnv env, Single<UploadVO> result) {
            super(env.api, env.session, new ImageCompressor(env.context), env.context);
            this.result = result;
        }

        @Override
        public Single<UploadVO> uploadAvatar(String uri) {
            return result;
        }
    }
}
