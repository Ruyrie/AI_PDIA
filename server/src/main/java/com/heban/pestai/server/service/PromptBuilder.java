package com.heban.pestai.server.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.heban.pestai.server.client.ChatMessage;
import com.heban.pestai.server.client.ChatPrompts;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;

/**
 * 组装发给模型的 messages：系统提示（角色设定 + 识别结论（如有）+ 输出格式约定）+ 最近 10 轮历史 + 当前问题。
 */
@Component
public class PromptBuilder {

    /** 10 轮问答 */
    public static final int HISTORY_LIMIT = 20;

    private final AiMessageMapper aiMessageMapper;
    private final RecognitionMapper recognitionMapper;

    public PromptBuilder(AiMessageMapper aiMessageMapper, RecognitionMapper recognitionMapper) {
        this.aiMessageMapper = aiMessageMapper;
        this.recognitionMapper = recognitionMapper;
    }

    /**
     * @param imageDataUrl 本轮带图时为图片 data 地址（改用识图模型回答），否则为 null
     */
    public List<ChatMessage> build(long userId, AiSession session, long questionId, String content, String imageDataUrl) {
        Recognition conclusion = null;
        if (session.getRecordId() != null) {
            conclusion = recognitionMapper.selectByIdAndUser(session.getRecordId(), userId);
        }
        List<AiMessage> history = new ArrayList<>(aiMessageMapper.selectRecent(session.getId(), questionId, HISTORY_LIMIT));
        Collections.reverse(history);
        return assemble(conclusion, history, content, imageDataUrl);
    }

    static List<ChatMessage> assemble(Recognition conclusion, List<AiMessage> history, String content, String imageDataUrl) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(conclusion)));
        for (AiMessage m : history) {
            if (m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            String role = ChatRole.ASSISTANT.equals(m.getRole()) ? ChatRole.ASSISTANT : ChatRole.USER;
            String text = m.getContent();
            if (ChatRole.USER.equals(role) && m.getImageUrl() != null && !m.getImageUrl().isBlank()) {
                text = text + ChatPrompts.IMAGE_NOTE;
            }
            append(messages, new ChatMessage(role, text, null));
        }
        ChatMessage current = ChatMessage.user(content);
        if (imageDataUrl != null) {
            current = current.withImage(imageDataUrl);
        }
        append(messages, current);
        return messages;
    }

    /** 相邻同角色消息合并（例如上一轮回答为空、未保存），保证 user / assistant 交替 */
    private static void append(List<ChatMessage> messages, ChatMessage next) {
        ChatMessage last = messages.get(messages.size() - 1);
        if (!ChatRole.SYSTEM.equals(last.role()) && last.role().equals(next.role())) {
            String image = next.imageDataUrl() != null ? next.imageDataUrl() : last.imageDataUrl();
            messages.set(messages.size() - 1, new ChatMessage(last.role(), last.text() + "\n" + next.text(), image));
        } else {
            messages.add(next);
        }
    }

    static String systemPrompt(Recognition conclusion) {
        StringBuilder sb = new StringBuilder(ChatPrompts.ROLE);
        String text = conclusionText(conclusion);
        if (text != null) {
            sb.append("\n\n").append(text);
        }
        sb.append("\n\n").append(ChatPrompts.FORMAT);
        return sb.toString();
    }

    /** 「本次对话针对的识别结论：黄瓜霜霉病，匹配度 92%，中度，卵菌病害，叶部，传播快，建议 3 天内处理；识别依据：……」 */
    static String conclusionText(Recognition r) {
        if (r == null || !RecognitionStatus.SUCCESS.equals(r.getStatus()) || r.getTopName() == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(r.getTopName());
        if (r.getConfidence() != null) {
            parts.add("匹配度 " + r.getConfidence().multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).toPlainString() + "%");
        }
        addIfPresent(parts, r.getSeverity());
        addIfPresent(parts, r.getCategory());
        addIfPresent(parts, r.getPart());
        if (Boolean.TRUE.equals(r.getSpreadFast())) {
            parts.add("传播快");
        }
        if (r.getUrgencyDays() != null) {
            parts.add("建议 " + r.getUrgencyDays() + " 天内处理");
        }
        StringBuilder sb = new StringBuilder(ChatPrompts.CONCLUSION_MARK).append(String.join("，", parts));
        if (r.getEvidence() != null && !r.getEvidence().isBlank()) {
            sb.append("；识别依据：").append(r.getEvidence());
        }
        sb.append("。请结合这一识别结论回答用户的追问。");
        return sb.toString();
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value);
        }
    }
}
