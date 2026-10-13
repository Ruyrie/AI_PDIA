package com.heban.pestai.server.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 阿里云 RPC 风格接口签名（SignatureMethod=HMAC-SHA1，SignatureVersion=1.0）：
 * <ol>
 *   <li>参数按名称字典序排序，名称与值分别做 percentEncode 后以 = 连接、& 拼接，得到规范化查询串；</li>
 *   <li>StringToSign = HTTPMethod + "&" + percentEncode("/") + "&" + percentEncode(规范化查询串)；</li>
 *   <li>Signature = Base64(HMAC-SHA1(AccessKeySecret + "&", StringToSign))。</li>
 * </ol>
 */
public final class AliyunRpcSigner {

    private AliyunRpcSigner() {
    }

    /** 规范化查询串（不含 Signature） */
    public static String canonicalQuery(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            joiner.add(percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()));
        }
        return joiner.toString();
    }

    public static String stringToSign(String httpMethod, String canonicalQuery) {
        return httpMethod + "&" + percentEncode("/") + "&" + percentEncode(canonicalQuery);
    }

    public static String sign(String httpMethod, Map<String, String> params, String accessKeySecret) {
        String toSign = stringToSign(httpMethod, canonicalQuery(params));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("计算阿里云签名失败", e);
        }
    }

    /** RFC 3986 编码：空格为 %20，* 为 %2A，~ 不编码 */
    public static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
