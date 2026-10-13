package com.heban.pestai.mine.ui.agreement;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.databinding.DataBindingUtil;

import com.heban.pestai.base.BaseActivity;
import com.heban.pestai.base.util.InsetsHelper;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.mine.R;
import com.heban.pestai.mine.databinding.ActivityAgreementBinding;

/**
 * 用户协议 / 隐私政策本地文本页（RouterPath.AGREEMENT，参数 EXTRA_AGREEMENT_TYPE = AGREEMENT_USER / AGREEMENT_PRIVACY）。
 * 正文来自 assets/agreement/*.html，用 HtmlCompat 转为 Spanned 显示在 TextView 中，不使用 WebView、不加载网络地址。
 * 模块02 登录页的协议链接也打开本页。
 */
public class AgreementActivity extends BaseActivity {

    /** 小标题字号（正文 14sp） */
    private static final int HEADING_SIZE_SP = 16;

    private ActivityAgreementBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_agreement);
        AgreementViewModel viewModel = obtainViewModel(AgreementViewModel.class);
        binding.setVm(viewModel);
        binding.setLifecycleOwner(this);
        InsetsHelper.applySystemBars(binding.agreementRoot);
        binding.agreementToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        viewModel.init(getIntent().getStringExtra(RouterPath.EXTRA_AGREEMENT_TYPE));
        binding.agreementToolbar.setTitle(RouterPath.AGREEMENT_PRIVACY.equals(viewModel.getType())
                ? R.string.mine_privacy_policy : R.string.mine_user_agreement);
        viewModel.getHtml().observe(this, html -> {
            if (html != null) {
                binding.agreementTvContent.setText(render(html));
            }
        });
    }

    /** HTML → Spanned；把标题的相对字号换成固定 16sp，并去掉末尾多余空行 */
    @NonNull
    private static CharSequence render(@NonNull String html) {
        Spanned spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY);
        SpannableStringBuilder sb = new SpannableStringBuilder(spanned);
        for (RelativeSizeSpan span : sb.getSpans(0, sb.length(), RelativeSizeSpan.class)) {
            int start = sb.getSpanStart(span);
            int end = sb.getSpanEnd(span);
            int flags = sb.getSpanFlags(span);
            sb.removeSpan(span);
            sb.setSpan(new AbsoluteSizeSpan(HEADING_SIZE_SP, true), start, end, flags);
        }
        int len = sb.length();
        while (len > 0 && Character.isWhitespace(sb.charAt(len - 1))) {
            len--;
        }
        sb.delete(len, sb.length());
        return sb;
    }
}
