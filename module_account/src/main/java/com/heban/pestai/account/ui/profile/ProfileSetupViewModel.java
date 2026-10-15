package com.heban.pestai.account.ui.profile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.heban.pestai.account.data.AccountRepository;
import com.heban.pestai.account.data.request.ProfileRequest;
import com.heban.pestai.account.ui.login.LoginEvent;
import com.heban.pestai.account.util.AccountTexts;
import com.heban.pestai.account.util.ProfileRules;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.network.ApiException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A06 完善资料 / 编辑资料：头像、昵称、种植身份、主要作物。
 * <ul>
 *     <li>完善资料（注册第 2 步，{@link #init()}）：进入时用本地缓存的 UserVO 预填（如默认昵称「农友5678」），可跳过；
 *     「开始使用」保存成功、「跳过」、返回键都发出 {@link LoginEvent#GO_RECOVERY_GUIDE}：先打开 A12 引导模式保存账号恢复码，
 *     再由 A12 进入首页（《找回账号与恢复码》六）。</li>
 *     <li>编辑资料（M01 我的进入，{@link #init(boolean)} 传 true）：先用缓存预填，再静默 GET user/me 以服务器为准
 *     （用户已开始修改时不覆盖）；「保存」成功后 UserSession 已更新（首页、我的页随 userChanges() 刷新），
 *     发出 {@link ProfileEditEvent#SAVED} 关闭页面，不进入 A12；返回时有未保存的修改发出
 *     {@link ProfileEditEvent#CONFIRM_DISCARD}，否则发出 {@link ProfileEditEvent#CLOSE}。</li>
 * </ul>
 * 「所在地区」已按产品负责人决定移除（不再收集、不再申请定位权限），保存时不提交 region，服务器保留原值。
 */
public class ProfileSetupViewModel extends BaseViewModel {

    public static final long CLICK_WINDOW_MS = 500;

    private final AccountRepository repository;

    private final MutableLiveData<UserVO> user = new MutableLiveData<>(null);
    /** 本地选图的 Uri 字符串（上传前预览；上传失败时保留） */
    private final MutableLiveData<String> avatarUri = new MutableLiveData<>(null);
    /** 上传成功后的网络地址（UploadVO.url），保存资料时作为 avatar_url 提交 */
    private final MutableLiveData<String> avatarUrl = new MutableLiveData<>(null);
    /** 头像显示来源：上传成功的网络地址 > 本地预览 > 原有头像 */
    private final MediatorLiveData<String> avatarImage = new MediatorLiveData<>(null);
    private final MutableLiveData<Boolean> avatarUploading = new MutableLiveData<>(false);
    private final MutableLiveData<String> nickname = new MutableLiveData<>("");
    private final MutableLiveData<String> identity = new MutableLiveData<>(ProfileRules.IDENTITIES.get(0));
    private final MutableLiveData<Set<String>> selectedCrops =
            new MutableLiveData<>(Collections.unmodifiableSet(new LinkedHashSet<>()));
    private final LiveData<String> cropTitle;
    private final MutableLiveData<Boolean> saving = new MutableLiveData<>(false);
    private final MediatorLiveData<Boolean> canSave = new MediatorLiveData<>(true);
    /** 完善资料模式离开本页的去向：固定为 {@link LoginEvent#GO_RECOVERY_GUIDE}（A12 引导模式），只发出一次 */
    private final SingleLiveEvent<LoginEvent> done = new SingleLiveEvent<>();
    /** 编辑资料模式（M01 我的 → 编辑资料）；首次完善资料为 false */
    private final MutableLiveData<Boolean> editMode = new MutableLiveData<>(false);
    /** 编辑资料模式的保存 / 返回事件 */
    private final SingleLiveEvent<ProfileEditEvent> editEvent = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> saveClicks = PublishSubject.create();

    @Nullable
    private String originalAvatar;
    /** 预填时的昵称（去首尾空格）、身份、作物：编辑资料模式据此判断是否有未保存的修改 */
    @NonNull
    private String originalNickname = "";
    @NonNull
    private String originalIdentity = ProfileRules.normalizeIdentity(null);
    @NonNull
    private Set<String> originalCrops = Collections.emptySet();
    private boolean edit;
    private boolean initialized;
    /** 已发出离开事件：避免快速连按「跳过」/ 返回键重复打开 A12，离开后也不再保存 */
    private boolean leaving;
    /** 编辑资料：进入时的 GET user/me；保存前取消，避免较早的资料晚到后覆盖刚保存的结果 */
    @Nullable
    private Disposable refreshRequest;

    @Inject
    public ProfileSetupViewModel(AccountRepository repository) {
        this.repository = repository;
        cropTitle = Transformations.map(selectedCrops, set -> AccountTexts.cropTitle(set == null ? 0 : set.size()));
        avatarImage.addSource(avatarUri, v -> updateAvatarImage());
        avatarImage.addSource(avatarUrl, v -> updateAvatarImage());
        canSave.addSource(saving, v -> updateCanSave());
        canSave.addSource(avatarUploading, v -> updateCanSave());
        add(saveClicks.throttleFirst(CLICK_WINDOW_MS, TimeUnit.MILLISECONDS).subscribe(x -> doSave()));
    }

    /** 完善资料（注册第 2 步）：用本地缓存的 UserVO 预填；只在首次创建时执行 */
    public void init() {
        init(false);
    }

    /**
     * 用本地缓存的 UserVO 预填；只在首次创建时执行（旋转屏幕后不重复）。
     *
     * @param editMode true 为编辑资料（M01 我的进入）：预填后再静默刷新 user/me
     */
    public void init(boolean editMode) {
        if (initialized) {
            return;
        }
        initialized = true;
        edit = editMode;
        this.editMode.setValue(editMode);
        applyUser(repository.cachedUser());
        if (editMode) {
            refreshUser();
        }
    }

    /** 用 UserVO 填充表单，并记下预填值作为「未修改」的基准 */
    private void applyUser(@Nullable UserVO source) {
        user.setValue(source);
        if (source != null) {
            nickname.setValue(source.getNickname() == null ? "" : source.getNickname());
            identity.setValue(ProfileRules.normalizeIdentity(source.getIdentity()));
            selectedCrops.setValue(Collections.unmodifiableSet(ProfileRules.splitCrops(source.getCrops())));
            originalAvatar = source.getAvatarUrl();
        } else {
            identity.setValue(ProfileRules.normalizeIdentity(null));
        }
        originalNickname = trimmed(nickname.getValue());
        originalIdentity = ProfileRules.normalizeIdentity(identity.getValue());
        Set<String> crops = selectedCrops.getValue();
        originalCrops = crops == null ? Collections.<String>emptySet() : crops;
        updateAvatarImage();
    }

    /** 编辑资料：GET user/me 成功且用户还没有开始修改时，用服务器资料重新预填；失败静默，继续使用缓存 */
    private void refreshUser() {
        refreshRequest = repository.refreshUser()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(fresh -> {
                    if (!leaving && !Boolean.TRUE.equals(saving.getValue()) && !hasChanges()) {
                        applyUser(fresh);
                    }
                }, e -> {
                    // 静默：页面已用缓存资料预填
                });
        add(refreshRequest);
    }

    // ---------- 头像 ----------

    /** 选图后：先显示本地预览，再压缩上传；成功后改用网络地址，失败保留本地预览并提示 */
    public void uploadAvatar(@NonNull String uri) {
        if (Boolean.TRUE.equals(avatarUploading.getValue())) {
            return;
        }
        avatarUrl.setValue(null);
        avatarUri.setValue(uri);
        avatarUploading.setValue(true);
        add(repository.uploadAvatar(uri)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> avatarUploading.setValue(false))
                .subscribe(vo -> avatarUrl.setValue(vo.getUrl()),
                        e -> showToast(AccountTexts.AVATAR_UPLOAD_FAILED)));
    }

    // ---------- 身份与作物 ----------

    public void selectIdentity(@NonNull String value) {
        if (!value.equals(identity.getValue())) {
            identity.setValue(value);
        }
    }

    /** 多选作物：在副本中加入或移除后重新赋值（LiveData 需要新对象才会通知观察者） */
    public void toggleCrop(@NonNull String crop) {
        Set<String> current = selectedCrops.getValue();
        Set<String> next = current == null ? new LinkedHashSet<>() : new LinkedHashSet<>(current);
        if (!next.remove(crop)) {
            next.add(crop);
        }
        selectedCrops.setValue(Collections.unmodifiableSet(next));
    }

    public boolean isCropSelected(@NonNull String crop) {
        Set<String> current = selectedCrops.getValue();
        return current != null && current.contains(crop);
    }

    // ---------- 保存 / 跳过 ----------

    /** 「开始使用」（完善资料）/「保存」（编辑资料） */
    public void save() {
        saveClicks.onNext(Boolean.TRUE);
    }

    /** 「跳过」（只在完善资料模式显示）：不保存资料，进入 A12 引导模式；编辑资料模式按返回处理 */
    public void skip() {
        if (edit) {
            onBack();
            return;
        }
        leave();
    }

    /**
     * 返回键 / 顶栏返回。完善资料：等同「跳过」（账号已创建，进入 A12 引导模式）；
     * 编辑资料：有未保存的修改时请页面确认（{@link ProfileEditEvent#CONFIRM_DISCARD}），否则直接关闭。
     */
    public void onBack() {
        if (!edit) {
            leave();
            return;
        }
        if (leaving) {
            return;
        }
        if (hasChanges()) {
            editEvent.setValue(ProfileEditEvent.CONFIRM_DISCARD);
        } else {
            close();
        }
    }

    /** 编辑资料：「放弃本次修改？」选择「放弃」 */
    public void discard() {
        close();
    }

    /**
     * 编辑资料：是否有未保存的修改。昵称按去首尾空格后比较，作物不计顺序；
     * 新选的头像（上传中、已上传，或上传失败仍在预览）都算修改。
     */
    public boolean hasChanges() {
        if (avatarUri.getValue() != null) {
            return true;
        }
        if (!originalNickname.equals(trimmed(nickname.getValue()))) {
            return true;
        }
        if (!originalIdentity.equals(ProfileRules.normalizeIdentity(identity.getValue()))) {
            return true;
        }
        Set<String> crops = selectedCrops.getValue();
        return !originalCrops.equals(crops == null ? Collections.<String>emptySet() : crops);
    }

    /** 编辑资料：没有修改或确认放弃，关闭页面 */
    private void close() {
        if (leaving) {
            return;
        }
        leaving = true;
        editEvent.setValue(ProfileEditEvent.CLOSE);
    }

    /** 账号已创建并登录：先以引导模式打开 A12 保存账号恢复码，再由 A12 进入首页 */
    private void leave() {
        if (leaving) {
            return;
        }
        leaving = true;
        done.setValue(LoginEvent.GO_RECOVERY_GUIDE);
    }

    private void doSave() {
        if (leaving || Boolean.TRUE.equals(saving.getValue()) || Boolean.TRUE.equals(avatarUploading.getValue())) {
            return;
        }
        String error = ProfileRules.nicknameError(nickname.getValue());
        if (error != null) {
            showToast(error);
            return;
        }
        if (refreshRequest != null) {
            // 进入时的 user/me 还没返回：取消，避免它晚到后用旧资料覆盖刚保存的 user_json
            refreshRequest.dispose();
            refreshRequest = null;
        }
        saving.setValue(true);
        add(repository.updateProfile(buildRequest())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> saving.setValue(false))
                .subscribe(this::onSaved, e -> showToast(ApiException.messageOf(e))));
    }

    /** 保存成功（仓库已用返回的 UserVO 覆盖 user_json 并通知其他组件） */
    private void onSaved(@NonNull UserVO vo) {
        user.setValue(vo);
        if (!edit) {
            leave();
            return;
        }
        // 编辑资料：关闭页面并提示「已保存」，不进入 A12
        leaving = true;
        editEvent.setValue(ProfileEditEvent.SAVED);
    }

    /** 组装 PUT user/profile 请求体；头像未上传成功时 avatar_url 为 null（不序列化，服务器保留原值） */
    @NonNull
    ProfileRequest buildRequest() {
        String name = trimmed(nickname.getValue());
        String id = ProfileRules.normalizeIdentity(identity.getValue());
        return new ProfileRequest(name, avatarUrl.getValue(), id, ProfileRules.joinCrops(selectedCrops.getValue()));
    }

    private void updateAvatarImage() {
        String url = avatarUrl.getValue();
        String local = avatarUri.getValue();
        avatarImage.setValue(url != null ? url : (local != null ? local : originalAvatar));
    }

    @NonNull
    private static String trimmed(@Nullable String text) {
        return text == null ? "" : text.trim();
    }

    private void updateCanSave() {
        canSave.setValue(!Boolean.TRUE.equals(saving.getValue()) && !Boolean.TRUE.equals(avatarUploading.getValue()));
    }

    // ---------- LiveData ----------

    public LiveData<UserVO> getUser() {
        return user;
    }

    public LiveData<String> getAvatarUri() {
        return avatarUri;
    }

    public LiveData<String> getAvatarUrl() {
        return avatarUrl;
    }

    public LiveData<String> getAvatarImage() {
        return avatarImage;
    }

    public LiveData<Boolean> getAvatarUploading() {
        return avatarUploading;
    }

    public MutableLiveData<String> getNickname() {
        return nickname;
    }

    public LiveData<String> getIdentity() {
        return identity;
    }

    public LiveData<Set<String>> getSelectedCrops() {
        return selectedCrops;
    }

    public LiveData<String> getCropTitle() {
        return cropTitle;
    }

    public LiveData<Boolean> getSaving() {
        return saving;
    }

    public LiveData<Boolean> getCanSave() {
        return canSave;
    }

    /** 完善资料模式离开本页（保存成功 / 跳过 / 返回）：{@link LoginEvent#GO_RECOVERY_GUIDE} */
    public LiveData<LoginEvent> getDone() {
        return done;
    }

    /** 是否为编辑资料模式（顶栏标题、按钮文字、隐藏步骤说明与「跳过」） */
    public LiveData<Boolean> getEditMode() {
        return editMode;
    }

    public boolean isEditMode() {
        return edit;
    }

    /** 编辑资料模式的保存 / 返回事件 */
    public LiveData<ProfileEditEvent> getEditEvent() {
        return editEvent;
    }
}
