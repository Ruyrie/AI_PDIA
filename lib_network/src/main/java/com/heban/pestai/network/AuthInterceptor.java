package com.heban.pestai.network;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.heban.pestai.common.session.SessionEvents;
import com.heban.pestai.common.session.UserSession;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 统一添加 Authorization: Bearer &lt;token&gt;；响应 HTTP 401 或业务码 40101 时
 * 清除登录态（token、user_json、is_guest）并发出「需要重新登录」事件。
 * 只检查 JSON 响应体（peekBody 不消耗原响应体）；流式 text/event-stream 响应不读取。
 */
@Singleton
public class AuthInterceptor implements Interceptor {

    private static final long PEEK_LIMIT = 64 * 1024;

    private final UserSession session;
    private final SessionEvents events;
    private final Gson gson;

    @Inject
    public AuthInterceptor(UserSession session, SessionEvents events, Gson gson) {
        this.session = session;
        this.events = events;
        this.gson = gson;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String token = session.getToken();
        boolean withToken = !TextUtils.isEmpty(token) && request.header("Authorization") == null;
        if (withToken) {
            request = request.newBuilder().header("Authorization", "Bearer " + token).build();
        }
        Response response = chain.proceed(request);
        // 只有带着令牌的请求被判定失效才清除登录态；游客请求需要登录的接口由页面自行提示
        if (withToken && isTokenInvalid(response)) {
            session.clear();
            events.postRelogin();
        }
        return response;
    }

    private boolean isTokenInvalid(Response response) {
        if (response.code() == 401) {
            return true;
        }
        ResponseBody body = response.body();
        MediaType type = body == null ? null : body.contentType();
        if (type == null || !"json".equalsIgnoreCase(type.subtype())) {
            return false;
        }
        try {
            String json = response.peekBody(PEEK_LIMIT).string();
            CodeOnly result = gson.fromJson(json, CodeOnly.class);
            return result != null && result.code == ApiException.UNAUTHORIZED;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class CodeOnly {
        int code;
    }
}
