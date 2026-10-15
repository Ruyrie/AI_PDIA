package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heban.pestai.server.client.ChatPrompts;
import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;

/**
 * 保存用户提问的短事务（独立 Bean，避免同类内部调用使 @Transactional 失效）。事务提交后立即释放数据库连接，
 * 之后的流式生成阶段不持有事务。
 */
@Service
public class AiMessageTxService {

    /** 普通对话用第一条提问补写标题：取问题文字前 30 个字 */
    public static final int TITLE_FROM_QUESTION = 30;
    /** 带图且没有文字的提问的标题 */
    public static final String IMAGE_TITLE = "图片提问";
    /**
     * 旧版 App 的 AiConstants.DEFAULT_IMAGE_QUESTION：旧版只能带 1 张图且 content 不能为空，用户没输入文字时以它作为
     * content 发送；新版 App 只发图片时 content 为空字符串。服务端把这两种情况都视为「带图且无文字」（标题为「图片提问」）；
     * 已保存的旧消息保持原样。
     */
    public static final String APP_DEFAULT_IMAGE_QUESTION = ChatPrompts.IMAGE_QUESTION;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final AiMessageMapper aiMessageMapper;
    private final AiSessionMapper aiSessionMapper;
    private final Clock clock;

    public AiMessageTxService(AiMessageMapper aiMessageMapper, AiSessionMapper aiSessionMapper, Clock clock) {
        this.aiMessageMapper = aiMessageMapper;
        this.aiSessionMapper = aiSessionMapper;
        this.clock = clock;
    }

    /**
     * 保存本轮问题，返回问题消息的 id。
     * <ul>
     *   <li>regenerate 为 false：插入 user 消息（content 为去掉首尾空白的文字，只发图片时为空字符串；image_url 为第一张图片），
     *       带图时按顺序插入 t_ai_message_image（与消息在同一事务）；</li>
     *   <li>regenerate 为 true：末尾是 assistant 且前一条 user 消息与本次 content、图片（相对路径及顺序）都相同 → 删除该回答并复用问题；
     *       末尾就是相同的 user 消息 → 直接复用；其余情况（服务器从未收到过该问题）按普通提问插入，不删除任何回答。</li>
     * </ul>
     * 普通对话（无 record_id）且 title 为空时（即第一条提问写入时）补写标题，见 {@link #titleOf}。
     * 插入了新消息时把会话 updated_at 更新为当前时间（补写标题的 UPDATE 同时更新 updated_at）。
     *
     * @param imageRelatives 本轮图片的相对路径（按顺序、已去重，最多 9 张）；没有图片时为空列表或 null
     */
    @Transactional(rollbackFor = Exception.class)
    public long saveQuestion(AiSession session, String content, List<String> imageRelatives, boolean regenerate) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> images = imageRelatives == null ? List.of() : List.copyOf(imageRelatives);
        Long questionId = null;
        if (regenerate) {
            List<AiMessage> tail = ChatImages.attach(aiMessageMapper, aiMessageMapper.selectTail(session.getId(), 2));
            AiMessage last = tail.isEmpty() ? null : tail.get(0);
            AiMessage previous = tail.size() > 1 ? tail.get(1) : null;
            if (last != null && ChatRole.ASSISTANT.equals(last.getRole())
                    && previous != null && sameQuestion(previous, content, images)) {
                aiMessageMapper.deleteById(last.getId());
                questionId = previous.getId();
            } else if (last != null && sameQuestion(last, content, images)) {
                questionId = last.getId();
            }
        }
        boolean inserted = false;
        if (questionId == null) {
            AiMessage message = new AiMessage(session.getId(), ChatRole.USER, content,
                    images.isEmpty() ? null : images.get(0), now);
            aiMessageMapper.insert(message);
            questionId = message.getId();
            if (!images.isEmpty()) {
                aiMessageMapper.insertImages(questionId, images, now);
            }
            inserted = true;
        }
        boolean needsTitle = session.getRecordId() == null
                && (session.getTitle() == null || session.getTitle().isBlank());
        String title = needsTitle ? titleOf(content, images) : null;
        if (title != null) {
            aiSessionMapper.updateTitle(session.getId(), title, now);
            session.setTitle(title);
            session.setUpdatedAt(now);
        } else if (inserted) {
            aiSessionMapper.touch(session.getId(), now);
            session.setUpdatedAt(now);
        }
        return questionId;
    }

    /**
     * 普通对话的标题：问题文字（换行与连续空白合并为一个空格）前 30 个字；
     * 带图（至少 1 张）且没有文字（content 为空或为旧版 App 的默认图片提问）时为「图片提问」。
     */
    static String titleOf(String content, List<String> imageRelatives) {
        String text = content == null ? "" : WHITESPACE.matcher(content).replaceAll(" ").strip();
        boolean withImage = imageRelatives != null && !imageRelatives.isEmpty();
        if (withImage && (text.isEmpty() || APP_DEFAULT_IMAGE_QUESTION.equals(text))) {
            return IMAGE_TITLE;
        }
        return text.isEmpty() ? null : Rules.truncate(text, TITLE_FROM_QUESTION);
    }

    /** 同一个问题：user 消息，content 相同，图片相对路径及顺序都相同（旧消息没有图片行时按 [image_url] 比较） */
    static boolean sameQuestion(AiMessage message, String content, List<String> imageRelatives) {
        List<String> images = imageRelatives == null ? List.of() : imageRelatives;
        return ChatRole.USER.equals(message.getRole())
                && Objects.equals(message.getContent(), content)
                && ChatImages.of(message).equals(images);
    }
}
