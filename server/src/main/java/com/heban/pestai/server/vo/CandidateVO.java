package com.heban.pestai.server.vo;

import java.math.BigDecimal;

/** 其他可能结果：匹配度 0–1。 */
public record CandidateVO(String name, BigDecimal confidence) {
}
