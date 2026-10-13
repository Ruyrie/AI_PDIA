package com.heban.pestai.recognize.util;

import static org.junit.Assert.assertEquals;

import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.constant.ErrorType;
import com.heban.pestai.common.constant.FailReason;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.recognize.R;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public class RecognizeErrorsTest {

    private static ApiException api(int code) {
        return ApiException.of(code);
    }

    @Test
    public void reasonOf_businessCodes() {
        assertEquals(FailReason.IMAGE_BAD, RecognizeErrors.reasonOf(api(42201)));
        assertEquals(FailReason.TIMEOUT, RecognizeErrors.reasonOf(api(50401)));
        assertEquals(FailReason.UNAVAILABLE, RecognizeErrors.reasonOf(api(50201)));
        assertEquals(FailReason.UNAVAILABLE, RecognizeErrors.reasonOf(api(50001)));
        assertEquals(FailReason.UNAVAILABLE, RecognizeErrors.reasonOf(api(40001)));
    }

    @Test
    public void reasonOf_localExceptions() {
        // App 读取超时
        assertEquals(FailReason.TIMEOUT, RecognizeErrors.reasonOf(new SocketTimeoutException()));
        assertEquals(FailReason.TIMEOUT, RecognizeErrors.reasonOf(ApiException.from(new SocketTimeoutException())));
        // 断网、连接失败
        assertEquals(FailReason.NETWORK, RecognizeErrors.reasonOf(new UnknownHostException()));
        assertEquals(FailReason.NETWORK, RecognizeErrors.reasonOf(ApiException.from(new ConnectException())));
        // 本机照片无法读取：按照片不清晰处理（ImageReadException 是 IOException，不能按网络异常）
        assertEquals(FailReason.IMAGE_BAD,
                RecognizeErrors.reasonOf(new ImageCompressor.ImageReadException("bad")));
    }

    @Test
    public void typeOf_mapsReasonToErrorType() {
        assertEquals(ErrorType.IMAGE_BAD, RecognizeErrors.typeOf(api(42201)));
        assertEquals(ErrorType.IMAGE_BAD, RecognizeErrors.typeOf(new ImageCompressor.ImageReadException("x")));
        assertEquals(ErrorType.FAILED, RecognizeErrors.typeOf(api(50401)));
        assertEquals(ErrorType.FAILED, RecognizeErrors.typeOf(new IOException()));
        assertEquals(ErrorType.FAILED, RecognizeErrors.typeOfReason(null));
    }

    @Test
    public void typeOfStatus() {
        assertEquals(ErrorType.IMAGE_BAD, RecognizeErrors.typeOfStatus("IMAGE_BAD"));
        assertEquals(ErrorType.FAILED, RecognizeErrors.typeOfStatus("FAILED"));
        assertEquals(ErrorType.FAILED, RecognizeErrors.typeOfStatus(null));
    }

    @Test
    public void codeOf() {
        assertEquals(42201, RecognizeErrors.codeOf(api(42201)));
        assertEquals(ApiException.TIMEOUT, RecognizeErrors.codeOf(new SocketTimeoutException()));
        assertEquals(ApiException.NETWORK, RecognizeErrors.codeOf(new UnknownHostException()));
        assertEquals(ApiException.NETWORK, RecognizeErrors.codeOf(new ImageCompressor.ImageReadException("x")));
    }

    @Test
    public void messageRes() {
        assertEquals(R.string.scan_err_image_bad, RecognizeErrors.messageRes(api(42201)));
        assertEquals(R.string.scan_err_unavailable, RecognizeErrors.messageRes(api(50201)));
        assertEquals(R.string.scan_err_timeout, RecognizeErrors.messageRes(api(50401)));
        assertEquals(R.string.scan_err_timeout, RecognizeErrors.messageRes(new SocketTimeoutException()));
        assertEquals(R.string.scan_err_server, RecognizeErrors.messageRes(api(50001)));
        assertEquals(R.string.scan_err_network, RecognizeErrors.messageRes(new UnknownHostException()));
        assertEquals(R.string.scan_err_image_unreadable,
                RecognizeErrors.messageRes(new ImageCompressor.ImageReadException("x")));
        // 40001 使用服务器文案（如「原照片已不存在，请重新拍摄」）
        assertEquals(0, RecognizeErrors.messageRes(ApiException.of(40001, "原照片已不存在，请重新拍摄")));
    }
}
