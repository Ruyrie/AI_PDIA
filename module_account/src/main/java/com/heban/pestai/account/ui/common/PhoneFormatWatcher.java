package com.heban.pestai.account.ui.common;

import android.text.Editable;
import android.text.Selection;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.heban.pestai.account.util.PhoneRules;

/**
 * 手机号输入时自动按 3-4-4 分隔（138 0000 5678），光标保持在原来的数字之后。
 * 与 DataBinding 双向绑定共存：格式化后的文本同样会写回 ViewModel，ViewModel 提交前用 PhoneRules.normalize() 去空格。
 */
public final class PhoneFormatWatcher implements TextWatcher {

    private boolean selfChange;

    public static void attach(@NonNull EditText editText) {
        editText.addTextChangedListener(new PhoneFormatWatcher());
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (selfChange) {
            return;
        }
        String current = s.toString();
        String formatted = PhoneRules.format(current);
        if (formatted.equals(current)) {
            return;
        }
        int cursor = Selection.getSelectionEnd(s);
        int digitsBefore = PhoneRules.digitsOf(cursor < 0 ? current : current.substring(0, Math.min(cursor, current.length())))
                .length();
        selfChange = true;
        try {
            s.replace(0, s.length(), formatted);
            Selection.setSelection(s, PhoneRules.cursorAfterDigits(formatted, digitsBefore));
        } finally {
            selfChange = false;
        }
    }
}
