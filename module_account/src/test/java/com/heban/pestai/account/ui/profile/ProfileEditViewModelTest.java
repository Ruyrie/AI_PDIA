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
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.ApiResponse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;

/** A06 编辑资料模式（M01 我的 → 编辑资料） */
public class ProfileEditViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rx = new RxTestRule();

    private TestEnv env;
    private ProfileSetupViewModel vm;
    private List<String> toasts;
    private List<LoginEvent> done;
    private List<ProfileEditEvent> events;

    @Before
    public void setUp() {
        env = new TestEnv().withLogin();
        // 本地缓存：已完善过资料的老用户
        UserVO cached = FakeAuthApi.user("老李种菜");
        cached.setAvatarUrl("http://10.0.2.2:8080/files/avatar/20261013/old.jpg");
        env.session.saveUser(cached);
        // user/me 与缓存一致
        UserVO fresh = FakeAuthApi.user("老李种菜");
        fresh.setAvatarUrl("http://10.0.2.2:8080/files/avatar/20261013/old.jpg");
        env.api.meResult = FakeAuthApi.ok(fresh);
        vm = create(env.repository);
    }

    private ProfileSetupViewModel create(AccountRepository repository) {
        ProfileSetupViewModel model = new ProfileSetupViewModel(repository);
        toasts = TestEnv.record(model.getToast());
        done = TestEnv.record(model.getDone());
        events = TestEnv.record(model.getEditEvent());
        TestEnv.record(model.getCropTitle());
        TestEnv.record(model.getAvatarImage());
        TestEnv.record(model.getCanSave());
        return model;
    }

    // ---------- 预填 ----------

    @Test
    public void init_prefillsFromCacheAndRefreshesUserMe() {
        vm.init(true);
        assertTrue(vm.isEditMode());
        assertEquals(Boolean.TRUE, vm.getEditMode().getValue());
        assertEquals("老李种菜", vm.getNickname().getValue());
        assertEquals("种植户", vm.getIdentity().getValue());
        assertEquals(3, vm.getSelectedCrops().getValue().size());
        assertEquals("http://10.0.2.2:8080/files/avatar/20261013/old.jpg", vm.getAvatarImage().getValue());
        assertEquals(1, env.api.meCount);
        assertFalse(vm.hasChanges());
    }

    @Test
    public void init_freshUserMeReplacesStaleCache() {
        UserVO fresh = FakeAuthApi.user("老李种番茄");
        fresh.setIdentity("农技人员");
        fresh.setCrops("草莓");
        fresh.setAvatarUrl("http://10.0.2.2:8080/files/avatar/20261014/new.jpg");
        env.api.meResult = FakeAuthApi.ok(fresh);

        vm.init(true);

        assertEquals("老李种番茄", vm.getNickname().getValue());
        assertEquals("农技人员", vm.getIdentity().getValue());
        assertEquals(Collections.singleton("草莓"), vm.getSelectedCrops().getValue());
        assertEquals(fresh.getAvatarUrl(), vm.getAvatarImage().getValue());
        assertFalse(vm.hasChanges());                           // 服务器资料成为新的基准
        assertTrue(env.userJson().contains("老李种番茄"));       // 同时刷新 user_json
    }

    @Test
    public void init_userMeArrivingAfterEditDoesNotOverwriteInput() {
        SingleSubject<ApiResponse<UserVO>> me = SingleSubject.create();
        env.api.meResult = me;
        vm.init(true);

        vm.getNickname().setValue("老李");
        me.onSuccess(new ApiResponse<>(0, "ok", FakeAuthApi.user("服务器昵称")));

        assertEquals("老李", vm.getNickname().getValue());
        assertTrue(vm.hasChanges());
    }

    @Test
    public void init_userMeFailureKeepsCachedValuesSilently() {
        env.api.meResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        vm.init(true);
        assertEquals("老李种菜", vm.getNickname().getValue());
        assertTrue(toasts.isEmpty());
    }

    @Test
    public void init_onlyOnceAfterRotation() {
        vm.init(true);
        vm.getNickname().setValue("老李");
        vm.init(true);
        assertEquals("老李", vm.getNickname().getValue());
        assertEquals(1, env.api.meCount);
    }

    // ---------- 修改检测 / 放弃确认 ----------

    @Test
    public void back_withoutChanges_closesDirectly() {
        vm.init(true);
        vm.getNickname().setValue(" 老李种菜 ");    // 只多了首尾空格：不算修改
        vm.toggleCrop("草莓");
        vm.toggleCrop("草莓");                      // 选了又取消：不算修改
        vm.selectIdentity("种植户");

        vm.onBack();

        assertEquals(Collections.singletonList(ProfileEditEvent.CLOSE), events);
        assertTrue(done.isEmpty());                // 不进入 A12
        assertEquals(0, env.api.updateProfileCount);
    }

    @Test
    public void back_withChanges_asksToDiscard() {
        vm.init(true);
        vm.getNickname().setValue("老李");
        vm.onBack();
        assertEquals(Collections.singletonList(ProfileEditEvent.CONFIRM_DISCARD), events);

        // 「继续编辑」：不处理，再按返回仍然确认
        vm.onBack();
        assertEquals(Arrays.asList(ProfileEditEvent.CONFIRM_DISCARD, ProfileEditEvent.CONFIRM_DISCARD), events);

        // 「放弃」：关闭页面，不保存
        vm.discard();
        assertEquals(ProfileEditEvent.CLOSE, events.get(events.size() - 1));
        assertEquals(0, env.api.updateProfileCount);
        assertTrue(env.userJson().contains("老李种菜"));
        assertTrue(done.isEmpty());
    }

    @Test
    public void hasChanges_detectsEachField() {
        vm.init(true);
        vm.selectIdentity("家庭园艺");
        assertTrue(vm.hasChanges());
        vm.selectIdentity("种植户");
        assertFalse(vm.hasChanges());

        vm.toggleCrop("番茄");
        assertTrue(vm.hasChanges());
        vm.toggleCrop("番茄");                      // 重新选上（顺序不同）：不算修改
        assertFalse(vm.hasChanges());
    }

    @Test
    public void hasChanges_newAvatarCountsEvenWhenUploadFails() {
        ProfileSetupViewModel editing = create(new FakeUploadRepository(env, Single.error(new java.io.IOException())));
        editing.init(true);
        assertFalse(editing.hasChanges());
        editing.uploadAvatar("content://com.heban.pestai.account.fileprovider/account_camera/AVATAR_1.jpg");
        assertTrue(editing.hasChanges());
        editing.onBack();
        assertEquals(Collections.singletonList(ProfileEditEvent.CONFIRM_DISCARD), events);
    }

    @Test
    public void skipInEditMode_behavesLikeBack() {
        vm.init(true);
        vm.skip();
        assertEquals(Collections.singletonList(ProfileEditEvent.CLOSE), events);
        assertTrue(done.isEmpty());
    }

    // ---------- 保存 ----------

    @Test
    public void save_success_updatesSessionAndClosesWithoutRecoveryGuide() {
        vm.init(true);
        UserVO saved = FakeAuthApi.user("老李种番茄");
        env.api.updateProfileResult = FakeAuthApi.ok(saved);
        vm.getNickname().setValue(" 老李种番茄 ");

        vm.save();

        ProfileRequest request = env.api.lastProfile;
        assertEquals("老李种番茄", request.getNickname());
        assertNull(request.getAvatarUrl());                 // 没换头像：不提交 avatar_url，服务器保留原头像
        assertFalse(env.gson.toJson(request).contains("avatar_url"));
        assertFalse(env.gson.toJson(request).contains("region"));
        assertEquals(Collections.singletonList(ProfileEditEvent.SAVED), events);
        assertTrue(done.isEmpty());                         // 不进入 A12 恢复码引导
        assertTrue(env.userJson().contains("老李种番茄"));    // user_json 已覆盖
        assertEquals(saved, env.session.userChanges().getValue());   // 首页、我的页随 userChanges() 刷新
        assertEquals(Boolean.FALSE, vm.getSaving().getValue());
    }

    @Test
    public void save_withNewAvatar_submitsUploadedUrl() {
        UploadVO upload = new UploadVO();
        upload.setUrl("http://10.0.2.2:8080/files/avatar/20261014/new.jpg");
        ProfileSetupViewModel editing = create(new FakeUploadRepository(env, Single.just(upload)));
        editing.init(true);

        editing.uploadAvatar("content://media/picker/1");
        assertEquals(upload.getUrl(), editing.getAvatarImage().getValue());
        editing.save();

        assertEquals(upload.getUrl(), env.api.lastProfile.getAvatarUrl());
        assertEquals(Collections.singletonList(ProfileEditEvent.SAVED), events);
    }

    @Test
    public void save_whileUploading_isIgnored() {
        SingleSubject<UploadVO> upload = SingleSubject.create();
        ProfileSetupViewModel editing = create(new FakeUploadRepository(env, upload));
        editing.init(true);
        editing.uploadAvatar("content://media/picker/1");
        assertEquals(Boolean.FALSE, editing.getCanSave().getValue());

        editing.save();
        assertEquals(0, env.api.updateProfileCount);
        assertTrue(events.isEmpty());
    }

    @Test
    public void save_failure_staysOnPage() {
        vm.init(true);
        env.api.updateProfileResult = FakeAuthApi.fail(ApiException.SERVER_ERROR, null);
        vm.getNickname().setValue("老李");
        vm.save();
        assertTrue(events.isEmpty());
        assertEquals("服务器开小差了，请稍后再试", toasts.get(toasts.size() - 1));
        // 仍可返回并确认放弃
        vm.onBack();
        assertEquals(Collections.singletonList(ProfileEditEvent.CONFIRM_DISCARD), events);
    }

    @Test
    public void save_cancelsPendingUserMeSoItCannotOverwriteSavedProfile() {
        SingleSubject<ApiResponse<UserVO>> me = SingleSubject.create();
        env.api.meResult = me;
        vm.init(true);
        env.api.updateProfileResult = FakeAuthApi.ok(FakeAuthApi.user("老李种番茄"));
        vm.getNickname().setValue("老李种番茄");
        vm.save();

        // 进入时的 user/me 晚到（服务器在保存前生成的旧资料）
        me.onSuccess(new ApiResponse<>(0, "ok", FakeAuthApi.user("老李种菜")));

        assertTrue(env.userJson().contains("老李种番茄"));
        assertFalse(me.hasObservers());
    }

    @Test
    public void afterSaved_furtherSaveOrBackIgnored() {
        vm.init(true);
        vm.save();
        rx.passThrottle();
        vm.save();
        vm.onBack();
        assertEquals(1, env.api.updateProfileCount);
        assertEquals(Collections.singletonList(ProfileEditEvent.SAVED), events);
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
