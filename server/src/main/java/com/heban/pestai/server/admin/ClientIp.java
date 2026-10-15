package com.heban.pestai.server.admin;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP：直连方是本机（127.0.0.1 / ::1，即 Nginx 转发）时才信任 X-Real-IP（Nginx 设为 $remote_addr），
 * 没有时取 X-Forwarded-For 的最后一项（Nginx 追加的那一项，前面的可以被客户端伪造）；否则取直连地址。
 * 请求头里的值只接受 IPv4 / IPv6 字符，不合法时退回直连地址。
 */
public final class ClientIp {

    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) {
            return remote;
        }
        String real = valid(request.getHeader("X-Real-IP"));
        if (real != null) {
            return real;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = valid(parts[i]);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return remote;
    }

    static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address);
    }

    private static String valid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return IP.matcher(trimmed).matches() ? trimmed : null;
    }
}
