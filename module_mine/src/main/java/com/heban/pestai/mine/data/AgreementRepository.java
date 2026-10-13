package com.heban.pestai.mine.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.router.RouterPath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 用户协议 / 隐私政策本地文本（assets/agreement/*.html，随安装包发布，离线可读，不请求网络）。
 */
@Singleton
public class AgreementRepository {

    static final String USER_FILE = "agreement/user_agreement.html";
    static final String PRIVACY_FILE = "agreement/privacy_policy.html";

    private final Context context;

    @Inject
    public AgreementRepository(Context context) {
        this.context = context;
    }

    /** 参数取值规范化：PRIVACY 以外（包括缺失与未知取值）一律按 USER 处理 */
    @NonNull
    public static String normalizeType(@Nullable String type) {
        return RouterPath.AGREEMENT_PRIVACY.equals(type) ? RouterPath.AGREEMENT_PRIVACY : RouterPath.AGREEMENT_USER;
    }

    @NonNull
    static String fileOf(@Nullable String type) {
        return RouterPath.AGREEMENT_PRIVACY.equals(normalizeType(type)) ? PRIVACY_FILE : USER_FILE;
    }

    /** io 线程按 UTF-8 读取协议 HTML */
    @NonNull
    public Single<String> load(@Nullable String type) {
        String file = fileOf(type);
        return Single.fromCallable(() -> {
            try (InputStream in = context.getAssets().open(file)) {
                return read(in);
            }
        }).subscribeOn(Schedulers.io());
    }

    @NonNull
    static String read(@NonNull InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
