package com.heban.pestai.server.common;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：BizException → 对应 code 与文案；参数校验失败 → 40001；其他 → 50001 并记录日志。
 * <p>
 * 业务错误的 HTTP 状态码统一为 200（由响应体 code 区分），并显式声明 Content-Type 为 application/json，
 * 这样流式接口（Accept: text/event-stream）在流开始前出错也能正常写出 JSON，而不会因内容协商变成 406。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return json(HttpStatus.OK, e.getCode(), e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException e) {
        // MethodArgumentNotValidException 是 BindException 的子类（@Valid @RequestBody）
        return param(firstMessage(e.getAllErrors()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException e) {
        String message = null;
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            message = customMessage(v.getMessageTemplate(), v.getMessage());
            if (message != null) {
                break;
            }
        }
        return param(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException e) {
        String message = null;
        for (MessageSourceResolvable error : e.getAllErrors()) {
            ConstraintViolation<?> v = unwrapViolation(error);
            if (v != null) {
                message = customMessage(v.getMessageTemplate(), v.getMessage());
                if (message != null) {
                    break;
                }
            }
        }
        return param(message);
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        log.debug("参数错误：{}", e.getMessage());
        return param(null);
    }

    /** 上传超过 2MB：语音识别接口提示「语音」，其余上传接口都是图片 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        return param(uri != null && uri.endsWith("/ai/asr") ? "语音不能超过 2MB" : "图片不能超过 2MB");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e) {
        log.debug("multipart 解析失败：{}", e.getMessage());
        return param("上传的文件不正确");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return json(HttpStatus.NOT_FOUND, ErrorCode.PARAM_ERROR.getCode(), "接口或资源不存在");
    }

    /** SseEmitter 超时：响应已经是 text/event-stream，不能再写 JSON，交给容器结束请求 */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.info("流式响应达到总时长上限，结束请求");
    }

    /** 客户端已断开（停止生成、断网），无需再写响应 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开：{}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未预料的异常", e);
        return json(HttpStatus.OK, ErrorCode.SERVER_ERROR.getCode(), ErrorCode.SERVER_ERROR.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> param(String message) {
        return json(HttpStatus.OK, ErrorCode.PARAM_ERROR.getCode(),
                message == null ? ErrorCode.PARAM_ERROR.getMessage() : message);
    }

    private static ResponseEntity<ApiResponse<Void>> json(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(code, message));
    }

    private static String firstMessage(List<ObjectError> errors) {
        for (ObjectError error : errors) {
            ConstraintViolation<?> v = unwrapViolation(error);
            if (v != null) {
                String message = customMessage(v.getMessageTemplate(), v.getMessage());
                if (message != null) {
                    return message;
                }
            } else if (error instanceof FieldError fe && fe.isBindingFailure()) {
                // 类型转换失败（如 page=abc），统一按参数错误
                return null;
            }
        }
        return null;
    }

    private static ConstraintViolation<?> unwrapViolation(MessageSourceResolvable error) {
        if (error instanceof ObjectError oe && oe.contains(ConstraintViolation.class)) {
            return oe.unwrap(ConstraintViolation.class);
        }
        return null;
    }

    /**
     * 只有在注解上显式写了中文 message（模板不是 {…} 形式）时才返回给 App；
     * Hibernate Validator 的默认文案（如「不能为空」）缺少字段名，对用户没有意义，统一显示「参数错误」。
     */
    private static String customMessage(String template, String message) {
        if (template == null || template.startsWith("{")) {
            return null;
        }
        return message;
    }
}
