package com.heban.pestai.server.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.client.VisionException;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.FailReason;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.config.VisionProperties;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.request.RecordPageRequest;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.RecognitionVO;
import com.heban.pestai.server.vo.RecordItemVO;

/**
 * 识别：上传识别、重新识别、详情、最近识别、分页、逻辑删除。
 * <p>
 * 上传识别与重新识别不加 @Transactional：识图最长 25 秒，放在事务中会长时间占用数据库连接；
 * 且失败分支要先写失败记录再抛出异常，若处于同一事务，失败记录会随异常回滚。写库只有一条语句，自动提交即可。
 */
@Service
public class RecognitionService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionService.class);

    static final String RECORD_NOT_FOUND = "该识别记录不存在或已删除";
    static final String DELETE_NOT_FOUND = "记录不存在";
    static final String IMAGE_MISSING = "原照片已不存在，请重新拍摄";

    private final RecognitionMapper recognitionMapper;
    private final FileStorageService fileStorageService;
    private final VisionClient visionClient;
    private final VisionProperties visionProperties;
    private final RecognitionAssembler assembler;
    private final Clock clock;

    public RecognitionService(RecognitionMapper recognitionMapper, FileStorageService fileStorageService,
                              VisionClient visionClient, VisionProperties visionProperties,
                              RecognitionAssembler assembler, Clock clock) {
        this.recognitionMapper = recognitionMapper;
        this.fileStorageService = fileStorageService;
        this.visionClient = visionClient;
        this.visionProperties = visionProperties;
        this.assembler = assembler;
        this.clock = clock;
    }

    /** POST recognitions：保存照片 → 识图（解析失败重试一次）→ 写库（成功、失败都写）→ 返回 VO 或错误码 */
    public RecognitionVO recognize(long userId, MultipartFile image, String mode) {
        byte[] bytes = fileStorageService.readImage(image);
        String relative = fileStorageService.save(bytes, FileStorageService.REC);
        String mime = FileStorageService.detectMime(bytes);

        long started = System.nanoTime();
        Outcome outcome = analyze(bytes, mime, mode);
        LocalDateTime now = LocalDateTime.now(clock);

        Recognition r = new Recognition();
        r.setUserId(userId);
        r.setMode(mode);
        r.setImageUrl(relative);
        r.setModelName(visionProperties.getDisplayName());
        r.setSpreadFast(false);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        if (outcome.result() != null) {
            fill(r, outcome.result());
            r.setStatus(RecognitionStatus.SUCCESS);
        } else {
            r.setStatus(outcome.status());
            r.setFailReason(outcome.failReason());
        }
        recognitionMapper.insert(r);
        log.info("识别完成：记录 {}，模式 {}，结果 {}，耗时 {} ms", r.getId(), mode, r.getStatus(),
                (System.nanoTime() - started) / 1_000_000);
        if (outcome.result() == null) {
            throw new BizException(outcome.errorCode());
        }
        return assembler.toVO(r);
    }

    /** POST recognitions/{id}/retry：用原照片重新识别；成功覆盖识别字段，失败只更新失败记录的原因 */
    public RecognitionVO retry(long userId, long id) {
        Recognition existing = recognitionMapper.selectByIdAndUser(id, userId);
        if (existing == null) {
            throw BizException.param(RECORD_NOT_FOUND);
        }
        byte[] bytes;
        try {
            bytes = fileStorageService.readBytes(existing.getImageUrl());
        } catch (IOException e) {
            throw BizException.param(IMAGE_MISSING);
        }
        String mime = FileStorageService.detectMime(bytes);
        if (mime == null) {
            mime = FileStorageService.mimeOfPath(existing.getImageUrl());
        }

        long started = System.nanoTime();
        Outcome outcome = analyze(bytes, mime, existing.getMode());
        LocalDateTime now = LocalDateTime.now(clock);
        log.info("重新识别：记录 {}，模式 {}，结果 {}，耗时 {} ms", id, existing.getMode(),
                outcome.result() != null ? RecognitionStatus.SUCCESS : outcome.status(),
                (System.nanoTime() - started) / 1_000_000);

        if (outcome.result() != null) {
            Recognition update = new Recognition();
            update.setId(id);
            update.setUserId(userId);
            fill(update, outcome.result());
            update.setModelName(visionProperties.getDisplayName());
            update.setUpdatedAt(now);
            if (recognitionMapper.updateResult(update) == 0) {
                throw BizException.param(RECORD_NOT_FOUND);
            }
            Recognition fresh = recognitionMapper.selectByIdAndUser(id, userId);
            if (fresh == null) {
                throw BizException.param(RECORD_NOT_FOUND);
            }
            return assembler.toVO(fresh);
        }
        // 原记录是成功记录则不写库，原结果原样保留；原记录是失败记录则只更新失败原因
        if (!RecognitionStatus.SUCCESS.equals(existing.getStatus())) {
            recognitionMapper.updateFailure(id, userId, outcome.status(), outcome.failReason(), now);
        }
        throw new BizException(outcome.errorCode());
    }

    /** GET recognitions/{id}：不存在、已删除、属于他人都返回 40001，不暴露他人记录是否存在 */
    public RecognitionVO detail(long userId, long id) {
        Recognition r = recognitionMapper.selectByIdAndUser(id, userId);
        if (r == null) {
            throw BizException.param(RECORD_NOT_FOUND);
        }
        return assembler.toVO(r);
    }

    /** GET recognitions/recent：按 created_at 倒序取前 size 条，不按 status 过滤 */
    public List<RecordItemVO> recent(long userId, int size) {
        return recognitionMapper.selectRecent(userId, size).stream().map(assembler::toItem).toList();
    }

    /** GET recognitions：分页；计数与列表在同一只读事务（一致性快照）中完成 */
    @Transactional(readOnly = true)
    public PageVO<RecordItemVO> page(long userId, RecordPageRequest request) {
        String mode = request.getMode() == null || request.getMode().isBlank() ? null : request.getMode();
        int page = request.getPage() == null ? 1 : request.getPage();
        int size = request.getSize() == null ? 20 : request.getSize();
        long offset = (long) (page - 1) * size;
        long total = recognitionMapper.countByUser(userId, mode);
        if (total == 0 || offset >= total) {
            return PageVO.empty(total);
        }
        List<RecordItemVO> list = recognitionMapper.selectPageByUser(userId, mode, offset, size)
                .stream().map(assembler::toItem).toList();
        return new PageVO<>(list, total, (long) page * size < total);
    }

    /** DELETE recognitions/{id}：逻辑删除；本人已删除的记录重复请求直接成功（幂等） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long userId, long id) {
        Recognition owner = recognitionMapper.selectOwnerById(id);
        if (owner == null || owner.getUserId() == null || owner.getUserId() != userId) {
            throw BizException.param(DELETE_NOT_FOUND);
        }
        if (owner.getDeleted() != null && owner.getDeleted() == 1) {
            return;
        }
        // 影响行数为 0（并发请求已先删除）同样视为成功
        recognitionMapper.logicDelete(id, userId, LocalDateTime.now(clock));
    }

    /**
     * 调用识图并校验结果：JSON 不合格时重试一次，重试与首次调用共用 25 秒的时间预算，
     * 剩余时间不足以重试时直接按不可用处理，保证在 App 的 30 秒读取超时之前给出响应。
     */
    Outcome analyze(byte[] bytes, String mime, String mode) {
        long budgetNanos = Duration.ofSeconds(visionProperties.getTimeoutSeconds()).toNanos();
        long deadline = System.nanoTime() + budgetNanos;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (attempt > 1 && remaining.toMillis() < visionProperties.getMinRetryMillis()) {
                log.warn("识图结果不合格，剩余时间 {} ms 不足以重试", remaining.toMillis());
                break;
            }
            if (remaining.isNegative() || remaining.isZero()) {
                return Outcome.failed(FailReason.TIMEOUT);
            }
            String raw;
            try {
                raw = visionClient.recognize(bytes, mime, mode, remaining);
            } catch (VisionException e) {
                log.warn("识图调用失败（第 {} 次）：{} {}", attempt, e.getReason(), e.getMessage());
                return Outcome.failed(e.getReason() == VisionException.Reason.TIMEOUT
                        ? FailReason.TIMEOUT : FailReason.UNAVAILABLE);
            }
            try {
                ParsedVision parsed = VisionResultParser.parse(raw, mode);
                return parsed.isSuccess() ? Outcome.success(parsed) : Outcome.imageBad();
            } catch (VisionResultParser.InvalidResultException e) {
                log.warn("识图结果不合格（第 {} 次）：{}", attempt, e.getMessage());
            }
        }
        return Outcome.failed(FailReason.UNAVAILABLE);
    }

    private static void fill(Recognition r, ParsedVision p) {
        r.setTopName(p.topName());
        r.setConfidence(p.confidence());
        r.setSeverity(p.severity());
        r.setCategory(p.category());
        r.setPart(p.part());
        r.setSpreadFast(p.spreadFast());
        r.setEvidence(p.evidence());
        r.setUrgencyDays(p.urgencyDays());
        r.setOthersJson(RecognitionAssembler.othersToJson(p.others()));
        r.setFailReason(null);
    }

    /** 识图结果：成功（result 非空），或 status + fail_reason + 错误码 */
    record Outcome(ParsedVision result, String status, String failReason, ErrorCode errorCode) {

        static Outcome success(ParsedVision result) {
            return new Outcome(result, RecognitionStatus.SUCCESS, null, ErrorCode.OK);
        }

        static Outcome imageBad() {
            return new Outcome(null, RecognitionStatus.IMAGE_BAD, FailReason.IMAGE_BAD, ErrorCode.IMAGE_BAD);
        }

        static Outcome failed(String failReason) {
            return new Outcome(null, RecognitionStatus.FAILED, failReason,
                    FailReason.TIMEOUT.equals(failReason) ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_UNAVAILABLE);
        }
    }
}
