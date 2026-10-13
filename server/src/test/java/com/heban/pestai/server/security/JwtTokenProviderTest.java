package com.heban.pestai.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.heban.pestai.server.common.BizException;
import com.heban.pestai.server.common.ErrorCode;

class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijklmnop";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-13T06:20:00Z");

    private final Clock clock = Clock.fixed(NOW, ZONE);

    @Test
    void issueAndParseRoundTrip() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, Duration.ofDays(30), clock);
        String token = provider.issue(10001L, 3);

        JwtTokenProvider.TokenClaims claims = provider.parse(token);
        assertThat(claims.userId()).isEqualTo(10001L);
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void largeUserIdSurvivesJsonNumberConversion() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, Duration.ofDays(30), clock);
        long big = 9_000_000_000L;
        assertThat(provider.parse(provider.issue(big, 0)).userId()).isEqualTo(big);
    }

    @Test
    void expiredTokenIsRejectedWith40101() {
        JwtTokenProvider issuer = new JwtTokenProvider(SECRET, Duration.ofDays(30), clock);
        String token = issuer.issue(1L, 0);

        Clock later = Clock.fixed(NOW.plus(Duration.ofDays(31)), ZONE);
        JwtTokenProvider verifier = new JwtTokenProvider(SECRET, Duration.ofDays(30), later);
        assertThatThrownBy(() -> verifier.parse(token))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = new JwtTokenProvider(SECRET, Duration.ofDays(30), clock).issue(1L, 0);
        JwtTokenProvider other = new JwtTokenProvider(SECRET + "-other", Duration.ofDays(30), clock);
        assertThatThrownBy(() -> other.parse(token)).isInstanceOf(BizException.class);
    }

    @Test
    void tamperedOrMalformedTokenIsRejected() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, Duration.ofDays(30), clock);
        String token = provider.issue(1L, 0);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        assertThatThrownBy(() -> provider.parse(tampered)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> provider.parse("not-a-jwt")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> provider.parse("")).isInstanceOf(BizException.class);
    }

    @Test
    void secretShorterThan32BytesFailsFast() {
        assertThatThrownBy(() -> new JwtTokenProvider("too-short", Duration.ofDays(30), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void blankSecretFallsBackToDevSecret() {
        JwtTokenProvider provider = new JwtTokenProvider("", Duration.ofDays(30), clock);
        assertThat(provider.isUsingDevSecret()).isTrue();
        assertThat(provider.parse(provider.issue(7L, 1)).userId()).isEqualTo(7L);
    }
}
