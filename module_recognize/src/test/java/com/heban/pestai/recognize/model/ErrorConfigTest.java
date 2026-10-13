package com.heban.pestai.recognize.model;

import static org.junit.Assert.assertEquals;

import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.recognize.R;

import org.junit.Test;

import java.util.Arrays;

public class ErrorConfigTest {

    @Test
    public void imageBad() {
        ErrorConfig c = ErrorConfig.of(ErrorType.IMAGE_BAD, FailReason.IMAGE_BAD, true);
        assertEquals(ErrorType.IMAGE_BAD, c.errorType);
        assertEquals(com.heban.pestai.base.R.drawable.ic_blur_on, c.iconRes);
        assertEquals(com.heban.pestai.base.R.attr.colorWarningContainer, c.iconBgAttr);
        assertEquals(com.heban.pestai.base.R.attr.colorOnWarningContainer, c.iconFgAttr);
        assertEquals(R.string.scan_error_blur_title, c.titleRes);
        assertEquals(R.string.scan_error_blur_message, c.messageRes);
        assertEquals(R.string.scan_error_blur_tips_title, c.tipsTitleRes);
        assertEquals(Arrays.asList(R.string.scan_error_blur_tip_1, R.string.scan_error_blur_tip_2,
                R.string.scan_error_blur_tip_3), c.tipRes);
        // 左「重新识别」、右「重拍」
        assertEquals(R.string.scan_error_action_recognize_again, c.secondaryTextRes);
        assertEquals(ErrorConfig.Action.RETRY, c.secondaryAction);
        assertEquals(com.heban.pestai.base.R.drawable.ic_refresh, c.secondaryIconRes);
        assertEquals(R.string.scan_error_action_retake, c.primaryTextRes);
        assertEquals(ErrorConfig.Action.RETAKE, c.primaryAction);
        assertEquals(com.heban.pestai.base.R.drawable.ic_photo_camera, c.primaryIconRes);
    }

    @Test
    public void imageBad_withoutRecord_stillHasThreeTips() {
        assertEquals(3, ErrorConfig.of(ErrorType.IMAGE_BAD, null, false).tipRes.size());
    }

    @Test
    public void failed_messageByReason() {
        assertEquals(R.string.scan_error_failed_message_timeout,
                ErrorConfig.of(ErrorType.FAILED, FailReason.TIMEOUT, true).messageRes);
        assertEquals(R.string.scan_error_failed_message_unavailable,
                ErrorConfig.of(ErrorType.FAILED, FailReason.UNAVAILABLE, true).messageRes);
        assertEquals(R.string.scan_error_failed_message_network,
                ErrorConfig.of(ErrorType.FAILED, FailReason.NETWORK, true).messageRes);
        // 空值与未知取值按 UNAVAILABLE
        ErrorConfig unknown = ErrorConfig.of(ErrorType.FAILED, "WHAT", true);
        assertEquals(FailReason.UNAVAILABLE, unknown.failReason);
        assertEquals(R.string.scan_error_failed_message_unavailable, unknown.messageRes);
        assertEquals(FailReason.UNAVAILABLE, ErrorConfig.of(ErrorType.FAILED, null, true).failReason);
    }

    @Test
    public void failed_iconButtonsAndTips() {
        ErrorConfig c = ErrorConfig.of(ErrorType.FAILED, FailReason.TIMEOUT, true);
        assertEquals(com.heban.pestai.base.R.drawable.ic_cloud_off, c.iconRes);
        assertEquals(com.google.android.material.R.attr.colorErrorContainer, c.iconBgAttr);
        assertEquals(R.string.scan_error_failed_title, c.titleRes);
        assertEquals(R.string.scan_error_failed_tips_title, c.tipsTitleRes);
        assertEquals(3, c.tipRes.size());
        // 左「重拍」、右「重试」
        assertEquals(ErrorConfig.Action.RETAKE, c.secondaryAction);
        assertEquals(R.string.scan_error_action_retake, c.secondaryTextRes);
        assertEquals(ErrorConfig.Action.RETRY, c.primaryAction);
        assertEquals(R.string.scan_error_action_retry, c.primaryTextRes);
    }

    @Test
    public void failed_withoutRecord_dropsThirdTip() {
        ErrorConfig c = ErrorConfig.of(ErrorType.FAILED, FailReason.NETWORK, false);
        assertEquals(Arrays.asList(R.string.scan_error_failed_tip_1, R.string.scan_error_failed_tip_2), c.tipRes);
    }

    @Test
    public void unknownType_isFailed() {
        assertEquals(ErrorType.FAILED, ErrorConfig.of("???", FailReason.TIMEOUT, true).errorType);
        assertEquals(ErrorType.FAILED, ErrorConfig.of(null, null, false).errorType);
    }
}
