package com.heban.pestai.server.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.heban.pestai.server.common.ApiResponse;
import com.heban.pestai.server.request.CreateSessionRequest;
import com.heban.pestai.server.request.MessageRequest;
import com.heban.pestai.server.security.UserContext;
import com.heban.pestai.server.service.AiChatService;
import com.heban.pestai.server.service.AiSessionService;
import com.heban.pestai.server.service.SuggestionService;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.SessionDetailVO;
import com.heban.pestai.server.vo.SessionItemVO;
import com.heban.pestai.server.vo.SessionVO;
import com.heban.pestai.server.vo.SuggestionVO;

/**
 * AI 问答：大家常问、新建会话、发送问题（流式）、历史对话（列表 / 打开 / 删除）。需要登录，只能访问自己的会话。
 * sessions/{id} 限定为数字。
 */
@Validated
@RestController
@RequestMapping("ai")
public class AiController {

    private static final MediaType EVENT_STREAM_UTF8 = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final SuggestionService suggestionService;
    private final AiSessionService aiSessionService;
    private final AiChatService aiChatService;

    public AiController(SuggestionService suggestionService, AiSessionService aiSessionService,
                        AiChatService aiChatService) {
        this.suggestionService = suggestionService;
        this.aiSessionService = aiSessionService;
        this.aiChatService = aiChatService;
    }

    /** GET ai/suggestions：大家常问（默认 3 条） */
    @GetMapping("suggestions")
    public ApiResponse<List<SuggestionVO>> suggestions() {
        return ApiResponse.success(suggestionService.listForUser(UserContext.requireUserId()));
    }

    /** POST ai/sessions：record_id 可空 */
    @PostMapping("sessions")
    public ApiResponse<SessionVO> createSession(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        Long recordId = request == null ? null : request.getRecordId();
        return ApiResponse.success(aiSessionService.create(UserContext.requireUserId(), recordId));
    }

    /** GET ai/sessions?page=1&size=20：历史对话（至少有 1 条消息的会话，updated_at 倒序；size 1–50） */
    @GetMapping("sessions")
    public ApiResponse<PageVO<SessionItemVO>> sessions(
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.success(aiSessionService.page(UserContext.requireUserId(), page, size));
    }

    /** GET ai/sessions/{id}：打开历史对话；不是自己的会话 40001 */
    @GetMapping("sessions/{id:\\d+}")
    public ApiResponse<SessionDetailVO> sessionDetail(@PathVariable("id") @Positive Long id) {
        return ApiResponse.success(aiSessionService.detail(UserContext.requireUserId(), id));
    }

    /** DELETE ai/sessions/{id}：删除会话与全部消息（幂等）；不是自己的会话 40001 */
    @DeleteMapping("sessions/{id:\\d+}")
    public ApiResponse<Void> deleteSession(@PathVariable("id") @Positive Long id) {
        aiSessionService.delete(UserContext.requireUserId(), id);
        return ApiResponse.success();
    }

    /**
     * POST ai/sessions/{id}/messages：流式返回（text/event-stream）。
     * 正文片段「data: 片段」，思考片段「event: reasoning」+「data: 片段」，结束「data: [DONE]」，
     * 生成途中出错「data: [ERROR]{"code":50201,...}」；保活注释行「:thinking」。
     * 流开始前的错误（40001、42901 等）以 application/json 的 ApiResponse 返回。
     */
    @PostMapping("sessions/{id}/messages")
    public ResponseEntity<SseEmitter> sendMessage(@PathVariable("id") @Positive Long id,
                                                  @Valid @RequestBody MessageRequest request) {
        // UserContext 基于 ThreadLocal，不会传递到异步线程，先在请求线程取出
        long userId = UserContext.requireUserId();
        AiChatService.PreparedChat prepared = aiChatService.prepare(userId, id, request);
        SseEmitter emitter = aiChatService.stream(prepared);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(EVENT_STREAM_UTF8)
                .body(emitter);
    }
}
