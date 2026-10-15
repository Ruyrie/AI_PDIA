package com.heban.pestai.server.vo;

import java.math.BigDecimal;

/**
 * 其他可能结果：匹配度 0–1；reason 为什么可能是它（照片里哪些特征像它），difference 与 top_name 的区别（田间怎么分辨）。
 * 模型没有给出时 reason / difference 为 null；旧识别记录的 others_json 中没有这两个字段，读取时同样为 null。
 */
public record CandidateVO(String name, BigDecimal confidence, String reason, String difference) {
}
