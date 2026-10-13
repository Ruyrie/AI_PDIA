package com.heban.pestai.account.ui.common;

import android.text.Editable;
import android.text.Selection;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.heban.pestai.account.util.RecoveryCodeRules;

/**
 * 恢复码输入时自动转大写、去掉空格等无关字符、按 4 位插入连字符（ABCD-EFGH-JKLM），最多 12 位有效字符；
 * 光标保持在原来的字符之后。粘贴「abcd efgh jklm」同样会整理成标准格式。
 * 与 DataBinding 双向绑定共存：格式化后的文本写回 ViewModel，提交前用 RecoveryCodeRules.normalize() 去掉连字符。
 */
public final class RecoveryCodeFormatWatcher implements TextWatcher {

    private boolean selfChange;

    public static void attach(@NonNull EditText editText) {
        editText.addTextChangedListener(new RecoveryCodeFormatWatcher());
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
        String formatted = RecoveryCodeRules.format(current);
        if (formatted.equals(current)) {
            return;
        }
        int cursor = Selection.getSelectionEnd(s);
        int before = RecoveryCodeRules.countCodeChars(current, cursor < 0 ? current.length() : cursor);
        selfChange = true;
        try {
            s.replace(0, s.length(), formatted);
            Selection.setSelection(s, RecoveryCodeRules.cursorAfterChars(formatted, before));
        } finally {
            selfChange = false;
        }
    }
}
