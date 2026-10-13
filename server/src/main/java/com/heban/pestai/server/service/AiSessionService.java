package com.heban.pestai.server.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.entity.AiMessage;
import com.heban.pestai.server.entity.AiSession;
import com.heban.pestai.server.entity.AiSessionSummary;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.AiMessageMapper;
import com.heban.pestai.server.mapper.AiSessionMapper;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.vo.ChatMessageVO;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.SessionDetailVO;
import com.heban.pestai.server.vo.SessionItemVO;
import com.heban.pestai.server.vo.SessionVO;

/**
 * AI 对话会话：新建、历史对话列表、打开历史对话、删除对话（都只能操作自己的会话）。
 * <ul>
 *   <li>新建：追问识别结果时（record_id 有值且为本人未删除的成功记录）标题为「追问：」+ top_name；
 *       记录已删除或不是成功记录时按普通对话处理（record_id、title 为空，第一条提问写入时补写标题）。</li>
 *   <li>历史对话：只列出至少有 1 条消息的会话，updated_at 倒序；title 为空时返回「新对话」。</li>
 *   <li>删除：删除会话与全部消息，事务提交后尽力删除聊天图片；会话不存在时直接成功（幂等）。</li>
 * </ul>
 */
@Service
public class AiSessionService {

    private static final Logger log = LoggerFactory.getLogger(AiSessionService.class);

    public static final int TITLE_MAX = 64;
    /** title 为空时（第一条提问还没写入）列表与详情显示的标题 */
    public static final String DEFAULT_TITLE = "新对话";
    static final String SESSION_NOT_FOUND = "会话不存在";
    static final String DELETE_FORBIDDEN = "无权删除该对话";

    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;
    private final RecognitionMapper recognitionMapper;
    private final RecognitionAssembler recognitionAssembler;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    public AiSessionService(AiSessionMapper aiSessionMapper, AiMessageMapper aiMessageMapper,
                            RecognitionMapper recognitionMapper, RecognitionAssembler recognitionAssembler,
                            FileStorageService fileStorageService, Clock clock) {
        this.aiSessionMapper = aiSessionMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.recognitionMapper = recognitionMapper;
        this.recognitionAssembler = recognitionAssembler;
        this.fileStorageService = fileStorageService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public SessionVO create(long userId, Long recordId) {
        Long sessionRecordId = null;
        String title = null;
        if (recordId != null) {
            Recognition record = recognitionMapper.selectOwnerById(recordId);
            if (record == null || record.getUserId() == null || record.getUserId() != userId) {
                throw BizException.param("该识别记录不存在或已删除");
            }
            boolean alive = record.getDeleted() == null || record.getDeleted() == 0;
            if (alive && RecognitionStatus.SUCCESS.equals(record.getStatus()) && record.getTopName() != null) {
                sessionRecordId = recordId;
                title = Rules.truncate("追问：" + record.getTopName(), TITLE_MAX);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AiSession session = new AiSession();
        session.setUserId(userId);
        session.setRecordId(sessionRecordId);
        session.setTitle(title);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        aiSessionMapper.insert(session);
        return new SessionVO(session.getId(), session.getRecordId(), session.getTitle());
    }

    /** GET ai/sessions：历史对话分页；计数与列表在同一只读事务（一致性快照）中完成 */
    @Transactional(readOnly = true)
    public PageVO<SessionItemVO> page(long userId, int page, int size) {
        long offset = (long) (page - 1) * size;
        long total = aiSessionMapper.countWithMessagesByUser(userId);
        if (total == 0 || offset >= total) {
            return PageVO.empty(total);
        }
        List<SessionItemVO> list = aiSessionMapper.selectPageWithMessages(userId, offset, size)
                .stream().map(AiSessionService::toItem).toList();
        return new PageVO<>(list, total, (long) page * size < total);
    }

    /** GET ai/sessions/{id}：会话信息 + 关联记录摘要（已删除时为 null）+ 全部消息正序；不是自己的会话 40001 */
    @Transactional(readOnly = true)
    public SessionDetailVO detail(long userId, long sessionId) {
        AiSession session = aiSessionMapper.selectById(sessionId);
        if (!ownedBy(session, userId)) {
            throw BizException.param(SESSION_NOT_FOUND);
        }
        Recognition record = session.getRecordId() == null
                ? null : recognitionMapper.selectByIdAndUser(session.getRecordId(), userId);
        List<ChatMessageVO> messages = aiMessageMapper.selectBySession(sessionId).stream()
                .map(this::toMessage).toList();
        return new SessionDetailVO(session.getId(), session.getRecordId(), titleOrDefault(session.getTitle()),
                recognitionAssembler.toItem(record), messages);
    }

    /**
     * DELETE ai/sessions/{id}：删除会话与全部消息；会话不存在（含已删除）直接成功，不是自己的会话 40001。
     * 要删除的图片路径在删除消息之前查出，事务提交后再删磁盘文件（回滚时文件保留）；仍被其他消息引用的图片不删。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long userId, long sessionId) {
        AiSession session = aiSessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        if (!ownedBy(session, userId)) {
            throw BizException.param(DELETE_FORBIDDEN);
        }
        Set<String> files = new LinkedHashSet<>();
        for (String path : aiMessageMapper.selectImageUrlsBySession(sessionId)) {
            if (path != null && path.startsWith(FileStorageService.CHAT + "/")) {
                files.add(path.trim());
            }
        }
        int messages = aiMessageMapper.deleteBySession(sessionId);
        aiSessionMapper.deleteByIdAndUser(sessionId, userId);
        if (!files.isEmpty()) {
            List<String> inUse = aiMessageMapper.selectImageUrlsInUse(List.copyOf(files));
            if (inUse != null) {
                inUse.forEach(files::remove);
            }
        }
        log.info("用户 {} 删除了会话 {}（消息 {} 条，待清理图片 {} 个）", userId, sessionId, messages, files.size());
        deleteFilesAfterCommit(List.copyOf(files));
    }

    static SessionItemVO toItem(AiSessionSummary s) {
        return new SessionItemVO(
                s.getId(),
                s.getRecordId(),
                titleOrDefault(s.getTitle()),
                MessagePreview.of(s.getLastContent(), s.getLastImageUrl()),
                s.getMessageCount() == null ? 0 : s.getMessageCount(),
                s.getUpdatedAt());
    }

    private ChatMessageVO toMessage(AiMessage m) {
        return new ChatMessageVO(m.getRole(), m.getContent() == null ? "" : m.getContent(),
                fileStorageService.toUrl(m.getImageUrl()), m.getCreatedAt(),
                m.getReasoningContent(), m.getReasoningMs());
    }

    static String titleOrDefault(String title) {
        return title == null || title.isBlank() ? DEFAULT_TITLE : title;
    }

    private static boolean ownedBy(AiSession session, long userId) {
        return session != null && session.getUserId() != null && session.getUserId() == userId;
    }

    /**
     * 事务提交成功后尽力删除聊天图片；失败只记日志，不影响删除结果（afterCommit 中的异常会传给调用方，因此全部吞掉）。
     * 没有事务时（单元测试直接调用）立即执行。
     */
    private void deleteFilesAfterCommit(List<String> files) {
        if (files.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            for (String path : files) {
                try {
                    if (!fileStorageService.deleteQuietly(path)) {
                        log.warn("删除对话：图片 {} 未能删除", path);
                    }
                } catch (RuntimeException e) {
                    log.warn("删除对话：删除图片 {} 失败：{}", path, e.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
