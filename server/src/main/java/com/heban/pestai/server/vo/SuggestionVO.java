package com.heban.pestai.server.vo;

/**
 * 大家常问。crop：按用户主要作物挑中的问题对应的作物名（用户作物中第一个属于该问题适用作物的），
 * 通用问题、与用户作物无关的问题、用户没有填写作物时为 null（App 据此显示「按你种的番茄、黄瓜推荐」）。
 */
public record SuggestionVO(Long id, String text, String crop) {
}
