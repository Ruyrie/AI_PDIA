package com.heban.pestai.server.config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 后台保存的密钥类配置（t_system_config.secret = 1）的加解密：AES-256-GCM，每次加密随机 12 字节 IV，
 * 密文格式「v1:」+ Base64(IV + 密文与 16 字节认证标签)。
 * <p>
 * 密钥 = SHA-256(固定标签 + 主密钥)：主密钥取环境变量 PESTAI_CONFIG_SECRET；未设置时取 JWT 签名密钥（JWT_SECRET，
 * 未配置时为开发默认值）。更换主密钥后，旧密文无法解密 —— RuntimeConfigService 记录告警并按「未设置」处理，需在后台重新填写。
 */
public final class ConfigCipher {

    static final String PREFIX = "v1:";
    private static final String LABEL_CONFIG_SECRET = "pestai-system-config|PESTAI_CONFIG_SECRET|";
    private static final String LABEL_JWT_SECRET = "pestai-system-config|JWT_SECRET|";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final boolean derivedFromJwtSecret;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param configSecret PESTAI_CONFIG_SECRET（可为空）
     * @param jwtSecret    实际使用的 JWT 签名密钥（configSecret 为空时由它派生）
     */
    public ConfigCipher(String configSecret, String jwtSecret) {
        boolean useConfigSecret = configSecret != null && !configSecret.isBlank();
        this.derivedFromJwtSecret = !useConfigSecret;
        String material = useConfigSecret
                ? LABEL_CONFIG_SECRET + configSecret.trim()
                : LABEL_JWT_SECRET + (jwtSecret == null ? "" : jwtSecret);
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    /** 未设置 PESTAI_CONFIG_SECRET、密钥由 JWT_SECRET 派生（启动日志提示：更换 JWT_SECRET 会让已保存的密钥失效） */
    public boolean isDerivedFromJwtSecret() {
        return derivedFromJwtSecret;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密配置失败", e);
        }
    }

    /**
     * 解密；格式不对、密钥不符（认证标签校验失败）、内容被篡改时抛出 GeneralSecurityException。
     */
    public String decrypt(String stored) throws GeneralSecurityException {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new GeneralSecurityException("不是本系统的密文格式");
        }
        byte[] all;
        try {
            all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("密文不是有效的 Base64", e);
        }
        if (all.length <= IV_BYTES + TAG_BITS / 8) {
            throw new GeneralSecurityException("密文长度不正确");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
        byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
