package com.heban.pestai.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ServerUrlInterceptorTest {

    private static final HttpUrl DEFAULT = HttpUrl.get("http://10.0.2.2:8080");

    private static Request get(String url) {
        return new Request.Builder().url(url).build();
    }

    @Test
    public void rewriteReplacesSchemeHostPortOnly() {
        Request request = new Request.Builder()
                .url("http://10.0.2.2:8080/pestai/v1/recognitions?page=2&size=20")
                .header("Authorization", "Bearer t")
                .post(RequestBody.create("{}", MediaType.get("application/json")))
                .build();

        Request result = ServerUrlInterceptor.rewrite(request, DEFAULT, HttpUrl.get("http://47.100.1.2"));

        assertEquals("http://47.100.1.2/pestai/v1/recognitions?page=2&size=20", result.url().toString());
        assertEquals("POST", result.method());
        assertEquals("Bearer t", result.header("Authorization"));
        assertSame(request.body(), result.body());
    }

    @Test
    public void rewriteToHttpsWithPort() {
        Request result = ServerUrlInterceptor.rewrite(get("http://10.0.2.2:8080/pestai/v1/user/me"),
                DEFAULT, HttpUrl.get("https://example.com:8443"));
        assertEquals("https://example.com:8443/pestai/v1/user/me", result.url().toString());
    }

    @Test
    public void otherServersAreUntouched() {
        Request image = get("http://10.0.2.2:9090/files/rec/a.jpg");
        Request other = get("https://dashscope.aliyuncs.com/v1");
        assertSame(image, ServerUrlInterceptor.rewrite(image, DEFAULT, HttpUrl.get("http://47.100.1.2")));
        assertSame(other, ServerUrlInterceptor.rewrite(other, DEFAULT, HttpUrl.get("http://47.100.1.2")));
    }

    @Test
    public void missingUrlsAreNoOp() {
        Request request = get("http://10.0.2.2:8080/pestai/v1/user/me");
        assertSame(request, ServerUrlInterceptor.rewrite(request, null, HttpUrl.get("http://47.100.1.2")));
        assertSame(request, ServerUrlInterceptor.rewrite(request, DEFAULT, null));
        assertSame(request, ServerUrlInterceptor.rewrite(request, DEFAULT, DEFAULT));
    }

    @Test
    public void interceptorFollowsServerAddressChanges() throws IOException {
        ServerAddressTest.InMemoryServerAddress address = new ServerAddressTest.InMemoryServerAddress("http://10.0.2.2:8080/");
        List<String> seen = new ArrayList<>();
        // 第二个拦截器直接返回响应，不发出真实请求
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ServerUrlInterceptor(address))
                .addInterceptor(chain -> {
                    seen.add(chain.request().url().toString());
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create("{}", MediaType.get("application/json")))
                            .build();
                })
                .build();
        String url = "http://10.0.2.2:8080/pestai/v1/user/me";

        client.newCall(get(url)).execute().close();
        address.set("47.100.1.2");
        client.newCall(get(url)).execute().close();
        address.set("47.100.1.3:8080");
        client.newCall(get(url)).execute().close();
        address.reset();
        client.newCall(get(url)).execute().close();

        List<String> expected = new ArrayList<>();
        expected.add(url);
        expected.add("http://47.100.1.2/pestai/v1/user/me");
        expected.add("http://47.100.1.3:8080/pestai/v1/user/me");
        expected.add(url);
        assertEquals(expected, seen);
    }
}
