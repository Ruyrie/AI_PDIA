package com.heban.pestai.ai.compose;

import android.text.TextUtils;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.databinding.BindingAdapter;

import com.heban.pestai.ai.R;

/**
 * module_ai 输入栏的 BindingAdapter（属性加 ai 前缀，不与 lib_base 的通用属性重名）。
 * 多条件文字用多属性 BindingAdapter，不在绑定表达式里写嵌套三元（见《开发约定》五、DataBinding）。
 * 按钮的显示 / 隐藏带过渡动画，由 {@link ComposerBar} 处理。
 */
public final class ComposerBindingAdapters {

    private ComposerBindingAdapters() {
    }

    /**
     * 输入框提示文字：没有图片时为页面默认提示（Q01「描述作物问题…」、Q02「继续追问…」），
     * 1 张为「描述这张照片的问题…」，2 张及以上为「描述这些照片的问题…」。
     */
    @BindingAdapter({"aiDefaultHint", "aiImageHintCount"})
    public static void setComposerHint(EditText view, @Nullable String defaultHint, int imageCount) {
        CharSequence hint;
        if (imageCount <= 0) {
            hint = defaultHint;
        } else if (imageCount == 1) {
            hint = view.getContext().getString(R.string.ai_input_hint_image);
        } else {
            hint = view.getContext().getString(R.string.ai_input_hint_images);
        }
        if (!TextUtils.equals(view.getHint(), hint)) {
            view.setHint(hint);
        }
    }
}
