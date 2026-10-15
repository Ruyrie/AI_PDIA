package com.heban.pestai.server.admin.vo;

import java.util.List;

/**
 * admin/api/stats/distribution：识别模式（「病虫害识别」「作物与杂草识别」）、识别成功的结果前 10、
 * 种植身份（清除了身份的用户为「未设置」）、用户作物前 10。
 */
public record StatsDistributionVO(
        List<NameValueVO> modes,
        List<NameValueVO> topResults,
        List<NameValueVO> identities,
        List<NameValueVO> crops) {
}
