package com.heban.pestai.server.common;

import java.util.List;

/** 严重程度：服务器直接返回中文。 */
public final class Severity {
    public static final String HEALTHY = "健康";
    public static final String MILD = "轻度";
    public static final String MODERATE = "中度";
    public static final String SEVERE = "较重";
    public static final List<String> VALUES = List.of(HEALTHY, MILD, MODERATE, SEVERE);

    private Severity() {
    }

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }
}
