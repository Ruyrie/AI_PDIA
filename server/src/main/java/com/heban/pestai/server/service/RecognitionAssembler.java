package com.heban.pestai.server.service;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.entity.Recognition;
import com.heban.pestai.server.vo.CandidateVO;
import com.heban.pestai.server.vo.RecognitionVO;
import com.heban.pestai.server.vo.RecordItemVO;

/**
 * Recognition → RecordItemVO / RecognitionVO：image_url 拼完整地址，confidence 0.920 → 0.92，
 * others_json 解析为 CandidateVO 数组（为空或损坏时返回空数组并记录告警；旧记录没有 reason / difference 字段，读取为 null，
 * 不认识的字段忽略）；user_id、deleted、others_json 不进 VO。
 */
@Component
public class RecognitionAssembler {

    private static final Logger log = LoggerFactory.getLogger(RecognitionAssembler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final TypeReference<List<CandidateVO>> CANDIDATES = new TypeReference<>() {
    };

    private final FileStorageService fileStorageService;

    public RecognitionAssembler(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    public RecordItemVO toItem(Recognition r) {
        if (r == null) {
            return null;
        }
        boolean success = RecognitionStatus.SUCCESS.equals(r.getStatus());
        return new RecordItemVO(
                r.getId(),
                r.getMode(),
                fileStorageService.toUrl(r.getImageUrl()),
                r.getStatus(),
                success ? r.getTopName() : null,
                success ? normalize(r.getConfidence()) : null,
                success ? r.getSeverity() : null,
                success ? null : r.getFailReason(),
                r.getCreatedAt());
    }

    public RecognitionVO toVO(Recognition r) {
        if (r == null) {
            return null;
        }
        boolean success = RecognitionStatus.SUCCESS.equals(r.getStatus());
        return new RecognitionVO(
                r.getId(),
                r.getMode(),
                fileStorageService.toUrl(r.getImageUrl()),
                r.getStatus(),
                success ? r.getTopName() : null,
                success ? normalize(r.getConfidence()) : null,
                success ? r.getSeverity() : null,
                success ? null : r.getFailReason(),
                r.getCreatedAt(),
                success ? r.getCategory() : null,
                success ? r.getPart() : null,
                success && Boolean.TRUE.equals(r.getSpreadFast()),
                success ? r.getEvidence() : null,
                success ? r.getUrgencyDays() : null,
                success ? parseOthers(r.getId(), r.getOthersJson()) : List.of(),
                r.getModelName());
    }

    public static String othersToJson(List<CandidateVO> others) {
        try {
            return MAPPER.writeValueAsString(others == null ? List.of() : others);
        } catch (Exception e) {
            return "[]";
        }
    }

    List<CandidateVO> parseOthers(Long id, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<CandidateVO> list = MAPPER.readValue(json, CANDIDATES);
            if (list == null) {
                return List.of();
            }
            return list.stream()
                    .map(c -> new CandidateVO(c.name(), normalize(c.confidence()), c.reason(), c.difference()))
                    .toList();
        } catch (Exception e) {
            log.warn("识别记录 {} 的 others_json 无法解析，按空数组返回：{}", id, e.getMessage());
            return List.of();
        }
    }

    /** 0.920 → 0.92（Jackson 以纯数字输出） */
    /** 0.920 → 0.92（去掉末尾的 0；后台识别记录列表也用它） */
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
