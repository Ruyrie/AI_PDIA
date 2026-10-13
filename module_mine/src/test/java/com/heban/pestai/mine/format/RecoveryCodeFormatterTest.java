package com.heban.pestai.mine.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.heban.pestai.common.vo.RecoveryCodeVO;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

public class RecoveryCodeFormatterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    private static RecoveryCodeVO status(boolean generated, LocalDate created) {
        RecoveryCodeVO vo = new RecoveryCodeVO();
        vo.setGenerated(generated);
        if (created != null) {
            vo.setCreatedAt(Date.from(created.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        return vo;
    }

    @Test
    public void subtitle() {
        assertEquals("手机号停用又忘记密码时用于找回账号", RecoveryCodeFormatter.subtitle(null, TODAY));
        assertEquals("未生成 · 手机号停用又忘记密码时用于找回账号",
                RecoveryCodeFormatter.subtitle(status(false, null), TODAY));
        assertEquals("已生成 · 10月13日", RecoveryCodeFormatter.subtitle(status(true, TODAY), TODAY));
        assertEquals("已生成 · 3月5日", RecoveryCodeFormatter.subtitle(status(true, LocalDate.of(2026, 3, 5)), TODAY));
        assertEquals("已生成 · 2025年12月31日",
                RecoveryCodeFormatter.subtitle(status(true, LocalDate.of(2025, 12, 31)), TODAY));
        assertEquals("已生成", RecoveryCodeFormatter.subtitle(status(true, null), TODAY));
    }

    @Test
    public void isMissing_onlyWhenKnownNotGenerated() {
        assertFalse(RecoveryCodeFormatter.isMissing(null));
        assertTrue(RecoveryCodeFormatter.isMissing(status(false, null)));
        assertFalse(RecoveryCodeFormatter.isMissing(status(true, TODAY)));
    }
}
