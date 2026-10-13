package com.heban.pestai.account.data.request;

/** 没有字段的 JSON 请求体，序列化为 {}（如 POST user/deactivate/sms：手机号由服务器按当前账号决定） */
public final class EmptyRequest {

    public static final EmptyRequest INSTANCE = new EmptyRequest();

    private EmptyRequest() {
    }
}
