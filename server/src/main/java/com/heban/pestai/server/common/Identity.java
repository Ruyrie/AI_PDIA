package com.heban.pestai.server.common;

/** 种植身份。 */
public final class Identity {
    public static final String GROWER = "种植户";
    public static final String GARDENER = "家庭园艺";
    public static final String TECHNICIAN = "农技人员";
    /** 用于 @Pattern 校验 */
    public static final String REGEX = "种植户|家庭园艺|农技人员";

    private Identity() {
    }
}
