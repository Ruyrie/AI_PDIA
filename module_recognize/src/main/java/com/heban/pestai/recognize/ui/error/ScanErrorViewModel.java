package com.heban.pestai.recognize.ui.error;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.SingleLiveEvent;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.data.RecognizeRepository;
import com.heban.pestai.recognize.model.ErrorConfig;
import com.heban.pestai.recognize.model.ErrorEvent;
import com.heban.pestai.recognize.model.RecognizeMode;
import com.heban.pestai.recognize.util.RecognizeErrors;
import com.heban.pestai.recognize.util.TextSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

/**
 * R02 识别异常：按 error_type、fail_reason、是否有记录选出 {@link ErrorConfig} 并写入 LiveData；
 * 「重新识别 / 重试」有记录时调用 retry 接口，没有记录时进入 S02 重新上传；「重拍」回到相机。
 */
public class ScanErrorViewModel extends BaseViewModel {

    private final RecognizeRepository repository;
    private final Gson gson;
    private final TextSource texts;

    private final MutableLiveData<String> errorType = new MutableLiveData<>(ErrorType.FAILED);
    private final MutableLiveData<Integer> icon = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> iconBgAttr = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> iconFgAttr = new MutableLiveData<>(0);
    private final MutableLiveData<String> title = new MutableLiveData<>("");
    private final MutableLiveData<String> message = new MutableLiveData<>("");
    private final MutableLiveData<String> tipsTitle = new MutableLiveData<>("");
    private final MutableLiveData<List<String>> tips = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<String> primaryText = new MutableLiveData<>("");
    private final MutableLiveData<Integer> primaryIcon = new MutableLiveData<>(0);
    private final MutableLiveData<String> secondaryText = new MutableLiveData<>("");
    private final MutableLiveData<Integer> secondaryIcon = new MutableLiveData<>(0);
    private final MutableLiveData<String> photo = new MutableLiveData<>();
    private final MutableLiveData<Boolean> retrying = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> primaryLoading = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> secondaryLoading = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> primaryVisible = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> secondaryVisible = new MutableLiveData<>(true);
    private final SingleLiveEvent<ErrorEvent> event = new SingleLiveEvent<>();

    private boolean initialized;
    private long recordId = RouterPath.NO_RECORD;
    @Nullable
    private String imageUrl;
    @Nullable
    private String imageUri;
    @NonNull
    private RecognizeMode mode = RecognizeMode.PEST;
    @Nullable
    private ErrorConfig config;
    /** 本机照片已不存在：只保留「重拍」 */
    private boolean retryAvailable = true;
    @Nullable
    private String resultJson;
    private long resultId = RouterPath.NO_RECORD;

    @Inject
    public ScanErrorViewModel(RecognizeRepository repository, Gson gson, TextSource texts) {
        this.repository = repository;
        this.gson = gson;
        this.texts = texts;
    }

    /** 只执行一次：保存参数，按异常类型、失败原因、是否有记录选出配置 */
    public void init(@Nullable String type, @Nullable String failReason, long id, @Nullable String url,
                     @Nullable String uri, @Nullable String extraMode) {
        if (initialized) {
            return;
        }
        initialized = true;
        recordId = id > 0 ? id : RouterPath.NO_RECORD;
        imageUrl = isEmpty(url) ? null : url;
        imageUri = isEmpty(uri) ? null : uri;
        mode = RecognizeMode.of(extraMode);
        photo.setValue(imageUrl != null ? imageUrl : imageUri);
        apply(ErrorConfig.of(type, failReason, hasRecord()));
    }

    public boolean hasRecord() {
        return recordId > 0;
    }

    /** 把配置逐项写入 LiveData；布局只绑定这些字段 */
    void apply(@NonNull ErrorConfig newConfig) {
        config = newConfig;
        errorType.setValue(newConfig.errorType);
        icon.setValue(newConfig.iconRes);
        iconBgAttr.setValue(newConfig.iconBgAttr);
        iconFgAttr.setValue(newConfig.iconFgAttr);
        title.setValue(texts.get(newConfig.titleRes));
        message.setValue(texts.get(newConfig.messageRes));
        tipsTitle.setValue(texts.get(newConfig.tipsTitleRes));
        List<String> list = new ArrayList<>(newConfig.tipRes.size());
        for (int res : newConfig.tipRes) {
            list.add(texts.get(res));
        }
        tips.setValue(list);
        primaryText.setValue(texts.get(newConfig.primaryTextRes));
        primaryIcon.setValue(newConfig.primaryIconRes);
        secondaryText.setValue(texts.get(newConfig.secondaryTextRes));
        secondaryIcon.setValue(newConfig.secondaryIconRes);
        refreshButtons();
    }

    private void refreshButtons() {
        if (config == null) {
            return;
        }
        boolean busy = Boolean.TRUE.equals(retrying.getValue());
        primaryLoading.setValue(busy && config.primaryAction == ErrorConfig.Action.RETRY);
        secondaryLoading.setValue(busy && config.secondaryAction == ErrorConfig.Action.RETRY);
        primaryVisible.setValue(retryAvailable || config.primaryAction != ErrorConfig.Action.RETRY);
        secondaryVisible.setValue(retryAvailable || config.secondaryAction != ErrorConfig.Action.RETRY);
    }

    // ---------- 按钮 ----------

    /** 右侧填充按钮 */
    public void onPrimaryClick() {
        if (config != null) {
            perform(config.primaryAction);
        }
    }

    /** 左侧描边按钮 */
    public void onSecondaryClick() {
        if (config != null) {
            perform(config.secondaryAction);
        }
    }

    private void perform(@NonNull ErrorConfig.Action action) {
        if (Boolean.TRUE.equals(retrying.getValue())) {
            return;
        }
        if (action == ErrorConfig.Action.RETAKE) {
            event.setValue(ErrorEvent.RETAKE);
        } else {
            retry();
        }
    }

    /**
     * 有记录：调用重新识别接口，成功进入 R01，失败按错误码切换状态并提示；
     * 没有记录（照片未上传成功）：确认本机照片仍在后进入 S02 重新上传。
     */
    public void retry() {
        if (Boolean.TRUE.equals(retrying.getValue()) || !retryAvailable) {
            return;
        }
        setRetrying(true);
        if (!hasRecord()) {
            add(repository.isReadable(imageUri)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally(() -> setRetrying(false))
                    .subscribe(readable -> {
                        if (readable) {
                            event.setValue(ErrorEvent.REUPLOAD);
                        } else {
                            onPhotoMissing();
                        }
                    }, e -> onPhotoMissing()));
            return;
        }
        add(repository.retry(recordId)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> setRetrying(false))
                .subscribe(this::onRetrySuccess, this::onRetryError));
    }

    private void onRetrySuccess(@NonNull RecognitionVO vo) {
        notifyRecordChanged();
        resultId = vo.getId() > 0 ? vo.getId() : recordId;
        resultJson = gson.toJson(vo);
        event.setValue(ErrorEvent.GO_RESULT);
    }

    private void onRetryError(@NonNull Throwable t) {
        int code = RecognizeErrors.codeOf(t);
        if (code > 0 && !RecognizeErrors.isUnauthorized(code)) {
            // 失败记录的 fail_reason 已由服务器更新，识别记录列表需要刷新
            notifyRecordChanged();
        }
        String reason = RecognizeErrors.reasonOf(t);
        String type = RecognizeErrors.typeOfReason(reason);
        apply(ErrorConfig.of(type, reason, hasRecord()));
        if (code == ApiException.IMAGE_BAD) {
            showToast(texts.get(R.string.scan_error_still_blur));
        } else {
            int res = RecognizeErrors.messageRes(t);
            showToast(res != 0 ? texts.get(res) : ApiException.messageOf(t));
        }
    }

    private void onPhotoMissing() {
        retryAvailable = false;
        refreshButtons();
        showToast(texts.get(R.string.scan_error_photo_missing));
    }

    private void setRetrying(boolean value) {
        retrying.setValue(value);
        refreshButtons();
    }

    public void openHistory() {
        event.setValue(ErrorEvent.OPEN_HISTORY);
    }

    private static void notifyRecordChanged() {
        try {
            ServiceHub.get(IRecordService.class).notifyChanged();
        } catch (IllegalStateException ignored) {
            // 组件单独运行时没有注册 IRecordService
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------- 跳转参数 ----------

    @Nullable
    public String getResultJson() {
        return resultJson;
    }

    public long getResultId() {
        return resultId;
    }

    @NonNull
    public String getModeValue() {
        return mode.value();
    }

    @Nullable
    public String getImageUri() {
        return imageUri;
    }

    public boolean isRetryAvailable() {
        return retryAvailable;
    }

    // ---------- getter ----------

    public LiveData<String> getErrorType() {
        return errorType;
    }

    public LiveData<Integer> getIcon() {
        return icon;
    }

    public LiveData<Integer> getIconBgAttr() {
        return iconBgAttr;
    }

    public LiveData<Integer> getIconFgAttr() {
        return iconFgAttr;
    }

    public LiveData<String> getTitle() {
        return title;
    }

    public LiveData<String> getMessage() {
        return message;
    }

    public LiveData<String> getTipsTitle() {
        return tipsTitle;
    }

    public LiveData<List<String>> getTips() {
        return tips;
    }

    public LiveData<String> getPrimaryText() {
        return primaryText;
    }

    public LiveData<Integer> getPrimaryIcon() {
        return primaryIcon;
    }

    public LiveData<String> getSecondaryText() {
        return secondaryText;
    }

    public LiveData<Integer> getSecondaryIcon() {
        return secondaryIcon;
    }

    public LiveData<String> getPhoto() {
        return photo;
    }

    public LiveData<Boolean> getRetrying() {
        return retrying;
    }

    public LiveData<Boolean> getPrimaryLoading() {
        return primaryLoading;
    }

    public LiveData<Boolean> getSecondaryLoading() {
        return secondaryLoading;
    }

    public LiveData<Boolean> getPrimaryVisible() {
        return primaryVisible;
    }

    public LiveData<Boolean> getSecondaryVisible() {
        return secondaryVisible;
    }

    public LiveData<ErrorEvent> getEvent() {
        return event;
    }
}
