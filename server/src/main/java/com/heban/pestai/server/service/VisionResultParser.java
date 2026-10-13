package com.heban.pestai.server.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.common.RecognitionStatus;
import com.heban.pestai.server.common.Rules;
import com.heban.pestai.server.common.Severity;
import com.heban.pestai.server.vo.CandidateVO;

/**
 * 解析并校验识图模型返回的固定 JSON：
 * <ul>
 *   <li>去掉可能附带的 Markdown 代码围栏，截取第一个「{」到最后一个「}」之间的文本；</li>
 *   <li>status 只能是 SUCCESS 或 IMAGE_BAD；SUCCESS 时 top_name 不能为空，confidence 在 0–1 并保留 3 位小数；</li>
 *   <li>PEST 模式 severity 必须是 健康 / 轻度 / 中度 / 较重 之一，CROP 模式 severity 置空；</li>
 *   <li>others 每条 name 非空、confidence 在 0–1（不合格的条目丢弃），按 confidence 降序最多保留 3 条。</li>
 * </ul>
 * 不合格时抛出 {@link InvalidResultException}，由 RecognitionService 重试一次。
 */
public final class VisionResultParser {

    public static final int MAX_OTHERS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal ONE = BigDecimal.ONE;

    private VisionResultParser() {
    }

    public static ParsedVision parse(String raw, String mode) throws InvalidResultException {
        if (raw == null || raw.isBlank()) {
            throw new InvalidResultException("模型返回为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new InvalidResultException("模型返回中没有 JSON 对象");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw.substring(start, end + 1));
        } catch (IOException e) {
            throw new InvalidResultException("JSON 解析失败：" + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new InvalidResultException("返回的不是 JSON 对象");
        }

        String status = text(field(root, "status"));
        if (status == null) {
            throw new InvalidResultException("缺少 status");
        }
        status = status.trim().toUpperCase(Locale.ROOT);
        if (RecognitionStatus.IMAGE_BAD.equals(status)) {
            return ParsedVision.imageBad();
        }
        if (!RecognitionStatus.SUCCESS.equals(status)) {
            throw new InvalidResultException("status 取值非法：" + status);
        }

        String topName = trimToNull(text(field(root, "top_name", "topName")));
        if (topName == null) {
            throw new InvalidResultException("缺少 top_name");
        }
        BigDecimal confidence = probability(field(root, "confidence"));
        if (confidence == null) {
            throw new InvalidResultException("confidence 缺失或不在 0–1 之间");
        }

        String severity = null;
        if (!Mode.CROP.equals(mode)) {
            severity = trimToNull(text(field(root, "severity")));
            if (!Severity.isValid(severity)) {
                throw new InvalidResultException("severity 取值非法：" + severity);
            }
        }

        String category = Rules.truncate(trimToNull(text(field(root, "category"))), 32);
        String part = Rules.truncate(trimToNull(text(field(root, "part"))), 32);
        boolean spreadFast = bool(field(root, "spread_fast", "spreadFast"));
        String evidence = Rules.truncate(trimToNull(text(field(root, "evidence"))), 500);
        Integer urgencyDays = urgency(field(root, "urgency_days", "urgencyDays"));
        List<CandidateVO> others = others(field(root, "others"), topName);

        return new ParsedVision(RecognitionStatus.SUCCESS, Rules.truncate(topName, 50), confidence, severity,
                category, part, spreadFast, evidence, urgencyDays, others);
    }

    private static List<CandidateVO> others(JsonNode node, String topName) {
        List<CandidateVO> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        Set<String> names = new HashSet<>();
        names.add(topName);
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String name = trimToNull(text(item.get("name")));
            BigDecimal confidence = probability(item.get("confidence"));
            if (name == null || confidence == null || !names.add(name)) {
                continue;
            }
            list.add(new CandidateVO(Rules.truncate(name, 50), confidence));
        }
        list.sort(Comparator.comparing(CandidateVO::confidence).reversed());
        return list.size() > MAX_OTHERS ? new ArrayList<>(list.subList(0, MAX_OTHERS)) : list;
    }

    /** 0–1 的小数，四舍五入保留 3 位（与 DECIMAL(4,3) 一致）；不合法返回 null */
    static BigDecimal probability(JsonNode node) {
        BigDecimal value = null;
        if (node != null && node.isNumber()) {
            value = node.decimalValue();
        } else if (node != null && node.isTextual()) {
            try {
                value = new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value == null || value.signum() < 0 || value.compareTo(ONE) > 0) {
            return null;
        }
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static Integer urgency(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Integer value = null;
        if (node.isNumber()) {
            value = (int) Math.round(node.asDouble());
        } else if (node.isTextual()) {
            String digits = node.asText().replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() <= 3) {
                value = Integer.parseInt(digits);
            }
        }
        return value == null || value < 0 || value > 365 ? null : value;
    }

    private static boolean bool(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String text = node.asText().trim().toLowerCase(Locale.ROOT);
            return "true".equals(text) || "是".equals(text);
        }
        return node.isNumber() && node.asInt() != 0;
    }

    private static JsonNode field(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : null;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    /** 识图结果不合格（解析失败、缺字段、字段越界） */
    public static class InvalidResultException extends Exception {
        public InvalidResultException(String message) {
            super(message);
        }
    }
}
