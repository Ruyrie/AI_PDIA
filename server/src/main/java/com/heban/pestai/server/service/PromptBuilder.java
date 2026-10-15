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
import com.heban.pestai.server.mapper.UserMapper;

/**
 * 组装发给模型的 messages：系统提示（角色设定 + 用户画像（如有）+ 识别结论（如有）+ 输出格式约定）+ 最近 10 轮历史 + 当前问题。
 * <ul>
 *   <li>用户画像取自用户资料的种植身份与主要作物（清洗规则见 UserProfile），两者都没有时不加；</li>
 *   <li>历史中的图片不再发给模型，只在文字后附说明：带文字的提问附「（用户附带了一张图片）」/「（用户附带了 N 张图片）」，
 *       只发图片的提问以「（用户发送了一张图片）」/「（用户发送了 N 张图片）」作为文字；</li>
 *   <li>当前问题带全部图片（先图片后文字）；只发图片时文字为「请帮我看看这张照片…」（1 张）或「请帮我看看这些照片…」（多张）。</li>
 * </ul>
 */
@Component
public class PromptBuilder {

    /** 10 轮问答 */
    public static final int HISTORY_LIMIT = 20;

    private final AiMessageMapper aiMessageMapper;
    private final RecognitionMapper recognitionMapper;
    private final UserMapper userMapper;

    public PromptBuilder(AiMessageMapper aiMessageMapper, RecognitionMapper recognitionMapper, UserMapper userMapper) {
        this.aiMessageMapper = aiMessageMapper;
        this.recognitionMapper = recognitionMapper;
        this.userMapper = userMapper;
    }

    /**
     * @param content       本轮问题文字（已去掉首尾空白，只发图片时为空字符串）
     * @param imageDataUrls 本轮图片的 data 地址（按顺序）；没有图片时为空列表或 null
     */
    public List<ChatMessage> build(long userId, AiSession session, long questionId, String content,
                                   List<String> imageDataUrls) {
        UserProfile profile = UserProfile.of(userMapper.selectById(userId));
        Recognition conclusion = null;
        if (session.getRecordId() != null) {
            conclusion = recognitionMapper.selectByIdAndUser(session.getRecordId(), userId);
        }
        List<AiMessage> history = new ArrayList<>(aiMessageMapper.selectRecent(session.getId(), questionId, HISTORY_LIMIT));
        ChatImages.attach(aiMessageMapper, history);
        Collections.reverse(history);
        return assemble(profile, conclusion, history, content, imageDataUrls);
    }

    static List<ChatMessage> assemble(UserProfile profile, Recognition conclusion, List<AiMessage> history,
                                      String content, List<String> imageDataUrls) {
        List<String> images = imageDataUrls == null ? List.of() : imageDataUrls;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt(profile, conclusion)));
        for (AiMessage m : history) {
            String text = historyText(m);
            if (text == null) {
                continue;
            }
            String role = ChatRole.ASSISTANT.equals(m.getRole()) ? ChatRole.ASSISTANT : ChatRole.USER;
            append(messages, new ChatMessage(role, text, List.of()));
        }
        append(messages, ChatMessage.user(questionText(content, images.size())).withImages(images));
        return messages;
    }

    /**
     * 历史消息发给模型的文字：user 消息带图时在文字后附图片说明，只发图片（content 为空）时以「（用户发送了 N 张图片）」作为文字；
     * 正文为空的其他消息（只有思考、正文还没开始就停止的回答）返回 null，跳过。
     */
    static String historyText(AiMessage m) {
        boolean blank = m.getContent() == null || m.getContent().isBlank();
        int images = ChatRole.ASSISTANT.equals(m.getRole()) ? 0 : ChatImages.of(m).size();
        if (images == 0) {
            return blank ? null : m.getContent();
        }
        return blank ? ChatPrompts.imageOnlyNote(images) : m.getContent() + ChatPrompts.imageNote(images);
    }

    /** 当前问题的文字：用户没有输入文字（只发图片）时按图片张数用默认问题 */
    static String questionText(String content, int imageCount) {
        if ((content == null || content.isBlank()) && imageCount > 0) {
            return ChatPrompts.imageQuestion(imageCount);
        }
        return content == null ? "" : content;
    }

    /** 相邻同角色消息合并（例如上一轮回答为空、未保存），保证 user / assistant 交替；图片按先后顺序合并 */
    private static void append(List<ChatMessage> messages, ChatMessage next) {
        ChatMessage last = messages.get(messages.size() - 1);
        if (!ChatRole.SYSTEM.equals(last.role()) && last.role().equals(next.role())) {
            List<String> images = new ArrayList<>(last.imageDataUrls());
            images.addAll(next.imageDataUrls());
            messages.set(messages.size() - 1, new ChatMessage(last.role(), last.text() + "\n" + next.text(), images));
        } else {
            messages.add(next);
        }
    }

    /** 角色设定 → 用户画像（如有）→ 识别结论（如有）→ 输出格式约定，段落之间空一行 */
    static String systemPrompt(UserProfile profile, Recognition conclusion) {
        StringBuilder sb = new StringBuilder(ChatPrompts.ROLE);
        appendParagraph(sb, profileText(profile));
        appendParagraph(sb, conclusionText(conclusion));
        appendParagraph(sb, ChatPrompts.FORMAT);
        return sb.toString();
    }

    private static void appendParagraph(StringBuilder sb, String text) {
        if (text != null) {
            sb.append("\n\n").append(text);
        }
    }

    /**
     * 「用户画像：」单独一行，其后为身份对应的回答侧重点（身份为空时没有）、主要作物（没有作物时没有），各占一行：
     * <pre>
     * 用户画像：
     * 用户是种植户（规模化田间 / 大棚生产）。给田间可操作的办法……
     * 用户主要种植：番茄、黄瓜、小麦。问题没有说明作物时……
     * </pre>
     * 身份与作物都没有时返回 null（不加这一段）。
     */
    static String profileText(UserProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(ChatPrompts.PROFILE_MARK);
        String guide = ChatPrompts.identityGuide(profile.identity());
        if (guide != null) {
            sb.append('\n').append(guide);
        }
        if (!profile.crops().isEmpty()) {
            sb.append('\n').append(ChatPrompts.cropsLine(profile.crops()));
        }
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
