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
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A06 完善资料（注册第 2 步）：头像、昵称、种植身份、主要作物；可跳过。
 * 进入时用本地缓存的 UserVO 预填（如默认昵称「农友5678」）。
 * 「开始使用」保存成功、「跳过」、返回键都发出 {@link LoginEvent#GO_RECOVERY_GUIDE}：先打开 A12 引导模式保存账号恢复码，
 * 再由 A12 进入首页（《找回账号与恢复码》六）。
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
    /** 离开本页的去向：固定为 {@link LoginEvent#GO_RECOVERY_GUIDE}（A12 引导模式），只发出一次 */
    private final SingleLiveEvent<LoginEvent> done = new SingleLiveEvent<>();
    private final PublishSubject<Boolean> saveClicks = PublishSubject.create();

    @Nullable
    private String originalAvatar;
    private boolean initialized;
    /** 已发出离开事件：避免快速连按「跳过」/ 返回键重复打开 A12 */
    private boolean leaving;

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

    /** 用本地缓存的 UserVO 预填；只在首次创建时执行 */
    public void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        UserVO cached = repository.cachedUser();
        user.setValue(cached);
        if (cached != null) {
            nickname.setValue(cached.getNickname() == null ? "" : cached.getNickname());
            identity.setValue(ProfileRules.normalizeIdentity(cached.getIdentity()));
            selectedCrops.setValue(Collections.unmodifiableSet(ProfileRules.splitCrops(cached.getCrops())));
            originalAvatar = cached.getAvatarUrl();
        } else {
            identity.setValue(ProfileRules.normalizeIdentity(null));
        }
        updateAvatarImage();
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

    /** 「开始使用」 */
    public void save() {
        saveClicks.onNext(Boolean.TRUE);
    }

    /** 「跳过」/ 返回键 / 顶栏返回：不保存资料，进入 A12 引导模式 */
    public void skip() {
        leave();
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
        saving.setValue(true);
        add(repository.updateProfile(buildRequest())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> saving.setValue(false))
                .subscribe(vo -> {
                    user.setValue(vo);
                    leave();
                }, e -> showToast(ApiException.messageOf(e))));
    }

    /** 组装 PUT user/profile 请求体；头像未上传成功时 avatar_url 为 null（不序列化，服务器保留原值） */
    @NonNull
    ProfileRequest buildRequest() {
        String name = nickname.getValue() == null ? "" : nickname.getValue().trim();
        String id = ProfileRules.normalizeIdentity(identity.getValue());
        return new ProfileRequest(name, avatarUrl.getValue(), id, ProfileRules.joinCrops(selectedCrops.getValue()));
    }

    private void updateAvatarImage() {
        String url = avatarUrl.getValue();
        String local = avatarUri.getValue();
        avatarImage.setValue(url != null ? url : (local != null ? local : originalAvatar));
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

    /** 离开本页（保存成功 / 跳过 / 返回）：{@link LoginEvent#GO_RECOVERY_GUIDE} */
    public LiveData<LoginEvent> getDone() {
        return done;
    }
}
