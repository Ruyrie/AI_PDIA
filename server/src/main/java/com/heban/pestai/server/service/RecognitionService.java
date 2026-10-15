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

import com.heban.pestai.server.client.FallbackBreaker;
import com.heban.pestai.server.client.VisionClient;
import com.heban.pestai.server.client.VisionException;
import com.heban.pestai.server.client.VisionFallback;
import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.common.FailReason;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.config.VisionProperties;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.mapper.RecognitionMapper;
import com.heban.pestai.server.mapper.UserMapper;
import com.heban.pestai.server.request.RecordPageRequest;
import com.heban.pestai.server.vo.PageVO;
import com.heban.pestai.server.vo.RecognitionVO;
import com.heban.pestai.server.vo.RecordItemVO;

/**
 * 识别：上传识别、重新识别、详情、最近识别、分页、逻辑删除。
 * 上传识别与重新识别都把用户的主要作物作为一行参考信息附在识图指令后（见 VisionPrompts.userInstruction），种植身份不参与识图。
 * 主模型（通义千问）失败时自动改用备用模型（DeepSeek，见 analyze 与 VisionFallback），App 不感知：model_name 仍为
 * vision.display-name，实际给出结果的模型与是否备用记录在 t_recognition.model / fallback（只供后台查看）。
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
    private final VisionFallback visionFallback;
    private final VisionProperties visionProperties;
    private final RecognitionAssembler assembler;
    private final UserMapper userMapper;
    private final Clock clock;

    public RecognitionService(RecognitionMapper recognitionMapper, FileStorageService fileStorageService,
                              VisionClient visionClient, VisionFallback visionFallback, VisionProperties visionProperties,
                              RecognitionAssembler assembler, UserMapper userMapper, Clock clock) {
        this.recognitionMapper = recognitionMapper;
        this.fileStorageService = fileStorageService;
        this.visionClient = visionClient;
        this.visionFallback = visionFallback;
        this.visionProperties = visionProperties;
        this.assembler = assembler;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    /** POST recognitions：保存照片 → 识图（解析失败重试一次，主模型失败时改用备用模型）→ 写库（成功、失败都写）→ 返回 VO 或错误码 */
    public RecognitionVO recognize(long userId, MultipartFile image, String mode) {
        byte[] bytes = fileStorageService.readImage(image);
        String relative = fileStorageService.save(bytes, FileStorageService.REC);
        String mime = FileStorageService.detectMime(bytes);
        List<String> userCrops = userCrops(userId);

        long started = System.nanoTime();
        Outcome outcome = analyze(bytes, mime, mode, userCrops);
        LocalDateTime now = LocalDateTime.now(clock);

        Recognition r = new Recognition();
        r.setUserId(userId);
        r.setMode(mode);
        r.setImageUrl(relative);
        r.setModelName(visionProperties.getDisplayName());
        r.setSpreadFast(false);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        r.setModel(outcome.model());
        r.setFallback(outcome.fallback());
        if (outcome.result() != null) {
            fill(r, outcome.result());
            r.setStatus(RecognitionStatus.SUCCESS);
        } else {
            r.setStatus(outcome.status());
            r.setFailReason(outcome.failReason());
        }
        recognitionMapper.insert(r);
        log.info("识别完成：记录 {}，模式 {}，结果 {}，模型 {}，耗时 {} ms", r.getId(), mode, r.getStatus(),
                modelLabel(outcome), (System.nanoTime() - started) / 1_000_000);
        logFallback(r.getId(), outcome);
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
        List<String> userCrops = userCrops(userId);

        long started = System.nanoTime();
        Outcome outcome = analyze(bytes, mime, existing.getMode(), userCrops);
        LocalDateTime now = LocalDateTime.now(clock);
        log.info("重新识别：记录 {}，模式 {}，结果 {}，模型 {}，耗时 {} ms", id, existing.getMode(),
                outcome.result() != null ? RecognitionStatus.SUCCESS : outcome.status(), modelLabel(outcome),
                (System.nanoTime() - started) / 1_000_000);
        logFallback(id, outcome);

        if (outcome.result() != null) {
            Recognition update = new Recognition();
            update.setId(id);
            update.setUserId(userId);
            fill(update, outcome.result());
            update.setModelName(visionProperties.getDisplayName());
            update.setModel(outcome.model());
            update.setFallback(outcome.fallback());
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
            recognitionMapper.updateFailure(id, userId, outcome.status(), outcome.failReason(), outcome.model(),
                    outcome.fallback(), now);
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

    /** 用户主要作物（清洗后，可为空列表）：作为识图参考信息，在开始计时之前读取，不占 25 秒预算 */
    private List<String> userCrops(long userId) {
        return UserProfile.cropsOf(userMapper.selectCropsById(userId));
    }

    /**
     * 调用识图并校验结果，总时间预算 25 秒（vision.timeout-seconds），保证在 App 的 30 秒读取超时之前给出响应。
     * userCrops 非空时由识图客户端在指令后附一行参考信息（每次调用相同）。
     * <ul>
     *   <li>自动备用不可用（开关 AI_FALLBACK_ENABLED 关闭、DEEPSEEK_API_KEY 未配置、主模型是 Mock）：与原来完全相同 ——
     *       主模型独占 25 秒，JSON 不合格时在剩余时间不少于 minRetryMillis 时重试一次，剩余时间不足以重试时按不可用处理；</li>
     *   <li>可用：主模型（含重试）最多用 15 秒（VisionFallback.primaryBudget）；调用失败（HTTP 错误、连接失败、超时）或两次都无法解析时
     *       记录熔断，并在剩余时间（约 10 秒）内改用备用模型（同样的提示词、图片与指令，同样的重试规则）。
     *       模型正常返回的「照片不清晰」是有效结果，不改用；</li>
     *   <li>熔断中（主模型 60 秒内失败过）：不再先试主模型，备用模型可用完整的 25 秒。</li>
     * </ul>
     * 备用模型也失败时按它的失败原因返回（超时 50401，其他 50201），不再回到主模型。
     */
    Outcome analyze(byte[] bytes, String mime, String mode, List<String> userCrops) {
        long deadline = System.nanoTime() + Duration.ofSeconds(visionProperties.getTimeoutSeconds()).toNanos();
        if (!visionFallback.available(visionClient)) {
            return attempt(visionClient, bytes, mime, mode, userCrops, deadline);
        }
        FallbackBreaker breaker = visionFallback.breaker();
        String reason;
        if (breaker.isOpen()) {
            reason = "主模型 " + visionClient.modelName() + " 最近失败过（熔断中，剩余约 " + breaker.remainingSeconds()
                    + " 秒），本次直接使用备用模型";
        } else {
            long primaryDeadline = System.nanoTime() + visionFallback.primaryBudget().toNanos();
            if (primaryDeadline - deadline > 0) {
                primaryDeadline = deadline;
            }
            Outcome primary = attempt(visionClient, bytes, mime, mode, userCrops, primaryDeadline);
            if (!RecognitionStatus.FAILED.equals(primary.status())) {
                return primary;
            }
            breaker.recordFailure();
            reason = "主模型 " + visionClient.modelName() + " 失败：" + FallbackBreaker.summary(primary.error());
        }
        VisionClient fallback = visionFallback.client();
        return attempt(fallback, bytes, mime, mode, userCrops, deadline).switchedTo(fallback.modelName(), reason);
    }

    /**
     * 用 client 识别（JSON 不合格时在剩余时间不少于 minRetryMillis 时重试一次）；deadline 为 System.nanoTime() 的截止时间。
     * 成功与照片不清晰时记录给出结果的模型名。
     */
    private Outcome attempt(VisionClient client, byte[] bytes, String mime, String mode, List<String> userCrops,
                            long deadline) {
        String invalid = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (attempt > 1 && remaining.toMillis() < visionProperties.getMinRetryMillis()) {
                log.warn("识图结果不合格，剩余时间 {} ms 不足以重试", remaining.toMillis());
                break;
            }
            if (remaining.isNegative() || remaining.isZero()) {
                return Outcome.failed(FailReason.TIMEOUT, "时间预算已用完");
            }
            String raw;
            try {
                raw = client.recognize(bytes, mime, mode, userCrops, remaining);
            } catch (VisionException e) {
                log.warn("识图调用失败（{}，第 {} 次）：{} {}", client.modelName(), attempt, e.getReason(), e.getMessage());
                return Outcome.failed(e.getReason() == VisionException.Reason.TIMEOUT
                        ? FailReason.TIMEOUT : FailReason.UNAVAILABLE, e.getReason() + " " + e.getMessage());
            }
            try {
                ParsedVision parsed = VisionResultParser.parse(raw, mode);
                String model = client.modelName();
                return parsed.isSuccess() ? Outcome.success(parsed, model) : Outcome.imageBad(model);
            } catch (VisionResultParser.InvalidResultException e) {
                log.warn("识图结果不合格（{}，第 {} 次）：{}", client.modelName(), attempt, e.getMessage());
                invalid = e.getMessage();
            }
        }
        return Outcome.failed(FailReason.UNAVAILABLE, "结果不合格：" + invalid);
    }

    /** 改用了备用模型时记一行 INFO：记录 id、主模型的失败原因（不含密钥）、备用模型与它的结果 */
    private static void logFallback(Long id, Outcome outcome) {
        Switched switched = outcome.switched();
        if (switched != null) {
            log.info("拍照识别改用备用模型 {}（记录 {}）：{}；备用模型结果 {}", switched.model(), id, switched.reason(),
                    RecognitionStatus.FAILED.equals(outcome.status())
                            ? outcome.status() + "（" + outcome.failReason() + "）" : outcome.status());
        }
    }

    /** 日志中的模型：备用模型加「（备用）」，失败为「-」 */
    private static String modelLabel(Outcome outcome) {
        if (outcome.model() == null) {
            return "-";
        }
        return outcome.fallback() ? outcome.model() + "（备用）" : outcome.model();
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

    /**
     * 识图结果：成功（result 非空）、照片不清晰，或失败（status + fail_reason + 错误码）。
     * model 为给出结果的模型名（成功与照片不清晰时有值，失败为 null）；error 为失败原因（写日志、作为改用备用模型的原因）；
     * switched 非空表示本次改用了备用模型（无论备用模型是否成功）。
     */
    record Outcome(ParsedVision result, String status, String failReason, ErrorCode errorCode,
                   String model, String error, Switched switched) {

        static Outcome success(ParsedVision result, String model) {
            return new Outcome(result, RecognitionStatus.SUCCESS, null, ErrorCode.OK, model, null, null);
        }

        static Outcome imageBad(String model) {
            return new Outcome(null, RecognitionStatus.IMAGE_BAD, FailReason.IMAGE_BAD, ErrorCode.IMAGE_BAD,
                    model, null, null);
        }

        static Outcome failed(String failReason, String error) {
            return new Outcome(null, RecognitionStatus.FAILED, failReason,
                    FailReason.TIMEOUT.equals(failReason) ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_UNAVAILABLE,
                    null, error, null);
        }

        /** 这是备用模型的结果：记下备用模型名与改用的原因 */
        Outcome switchedTo(String fallbackModel, String reason) {
            return new Outcome(result, status, failReason, errorCode, model, error, new Switched(fallbackModel, reason));
        }

        /** 是否由备用模型给出了结果（成功或照片不清晰）；写入 t_recognition.fallback */
        boolean fallback() {
            return switched != null && !RecognitionStatus.FAILED.equals(status);
        }
    }

    /** 改用了备用模型：备用模型名与主模型的失败原因摘要 */
    record Switched(String model, String reason) {
    }
}
