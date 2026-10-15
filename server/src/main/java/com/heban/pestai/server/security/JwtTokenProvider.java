package com.heban.pestai.server.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;
import com.heban.pestai.server.config.PestaiProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 用 jjwt 0.12.6 签发与校验 JWT（HS256）：载荷包含 userId 与 token_version，有效期 30 天，密钥来自环境变量 JWT_SECRET。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_TOKEN_VERSION = "token_version";

    /** 仅供本机开发：未配置 JWT_SECRET 时使用，生产环境必须配置 */
    static final String DEV_SECRET = "pestai-dev-only-jwt-secret-change-me-in-production-0123456789";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;
    private final boolean usingDevSecret;

    @Autowired
    public JwtTokenProvider(PestaiProperties properties, Clock clock) {
        this(properties.getJwt().getSecret(), Duration.ofDays(properties.getJwt().getTtlDays()), clock);
    }

    public JwtTokenProvider(String secret, Duration ttl, Clock clock) {
        boolean blank = secret == null || secret.isBlank();
        this.usingDevSecret = blank;
        String effective = effectiveSecret(secret);
        byte[] bytes = effective.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET 至少需要 32 字节（256 位），当前只有 " + bytes.length + " 字节");
        }
        if (blank) {
            log.warn("未配置环境变量 JWT_SECRET，正在使用开发默认密钥；生产环境必须配置 JWT_SECRET（至少 32 字节的随机串）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
        this.clock = clock;
    }

    public boolean isUsingDevSecret() {
        return usingDevSecret;
    }

    /** 实际使用的签名密钥：未配置 JWT_SECRET 时为开发默认值（后台配置加密在未设置 PESTAI_CONFIG_SECRET 时由它派生密钥） */
    public static String effectiveSecret(String configured) {
        return configured == null || configured.isBlank() ? DEV_SECRET : configured;
    }

    /** 签发令牌 */
    public String issue(long userId, int tokenVersion) {
        Instant now = clock.instant();
        return Jwts.builder()
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验签名与过期时间，返回载荷；任一项不通过抛出 40101 */
    public TokenClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object userId = claims.get(CLAIM_USER_ID);
            Object version = claims.get(CLAIM_TOKEN_VERSION);
            if (!(userId instanceof Number) || !(version instanceof Number)) {
                throw new BizException(ErrorCode.TOKEN_INVALID);
            }
            return new TokenClaims(((Number) userId).longValue(), ((Number) version).intValue());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
    }

    /** 令牌载荷 */
    public record TokenClaims(long userId, int tokenVersion) {
    }
}
