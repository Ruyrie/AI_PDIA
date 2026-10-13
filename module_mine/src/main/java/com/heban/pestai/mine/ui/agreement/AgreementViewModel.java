package com.heban.pestai.mine.ui.agreement;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.base.BaseViewModel;
import com.heban.pestai.base.UiState;
import com.heban.pestai.mine.data.AgreementRepository;

import javax.inject.Inject;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

/**
 * 用户协议 / 隐私政策页：io 线程读取 assets 中的 HTML，结果保存在 ViewModel 中，旋转屏幕不重复读取。
 */
public class AgreementViewModel extends BaseViewModel {

    private final AgreementRepository repository;

    private final MutableLiveData<String> html = new MutableLiveData<>();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.LOADING);
    private String type;

    @Inject
    public AgreementViewModel(AgreementRepository repository) {
        this.repository = repository;
    }

    /** 页面创建时调用；type 缺失或取值不认识时按用户协议处理。重复调用（旋转屏幕）直接返回 */
    public void init(@Nullable String type) {
        if (this.type != null) {
            return;
        }
        this.type = AgreementRepository.normalizeType(type);
        load();
    }

    public void load() {
        add(repository.load(type)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(d -> uiState.setValue(UiState.LOADING))
                .subscribe(text -> {
                    html.setValue(text);
                    uiState.setValue(UiState.SUCCESS);
                }, t -> uiState.setValue(UiState.ERROR)));
    }

    /** 规范化后的类型：RouterPath.AGREEMENT_USER / AGREEMENT_PRIVACY */
    public String getType() {
        return type;
    }

    public LiveData<String> getHtml() {
        return html;
    }

    public LiveData<UiState> getUiState() {
        return uiState;
    }
}
