package com.cl.agent.sql.core;

import com.cl.agent.exception.BizException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES/GCM 对称加解密服务。
 *
 * <h2>用途</h2>
 * 对宿主存入 {@code t_datasource.password_cipher} 的数据库密码字段加解密。算法选择 AES-256-GCM：
 * <ul>
 *   <li>对称加密性能足够（每次仅几百字节）；</li>
 *   <li>GCM 模式自带认证（防篡改），无需额外 HMAC；</li>
 *   <li>密文格式 {@code base64(IV ‖ ciphertext ‖ authTag)}，IV 每次随机，相同明文产生不同密文。</li>
 * </ul>
 *
 * <h2>密钥派生</h2>
 * 配置项 {@code agent.sql.crypto-key} 经 SHA-256 派生为 32 字节 AES 密钥；
 * 建议通过环境变量 {@code SQL_DS_CRYPTO_KEY} 注入，避免明文进 yml。
 *
 * <h2>线程安全</h2>
 * {@link Cipher} 实例**非**线程安全，故每次加/解密都新建实例；性能损耗在密码量级下可忽略。
 */
@Slf4j
public class CryptoService {

    /** GCM 认证标签长度（位） */
    private static final int GCM_TAG_BITS = 128;

    /** GCM 初始化向量长度（字节）；12 字节是 GCM 推荐值 */
    private static final int IV_BYTES = 12;

    /** AES 算法标识 */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 派生后的 AES 密钥 */
    private final SecretKey secretKey;

    /** 线程局部随机数生成器，每次 encrypt 都需要新 IV */
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造方法。
     *
     * @param props 全局配置；从 {@link SqlAgentProperties#getCryptoKey()} 取原始密钥串
     * @throws BizException 密钥为空或长度不足时
     */
    public CryptoService(SqlAgentProperties props) {
        String raw = props.getCryptoKey();
        if (raw == null || raw.isBlank()) {
            // 启动期不直接抛错：仅日志告警；首次调用 encrypt/decrypt 才抛
            log.warn("[CryptoService] agent.sql.crypto-key 未配置，加解密功能将不可用");
            this.secretKey = null;
            return;
        }
        if (raw.length() < 16) {
            throw new BizException(500, "agent.sql.crypto-key 长度过短，建议至少 16 字符");
        }
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(hashed, "AES");
            log.info("[CryptoService] AES-GCM 加解密服务初始化完成 (密钥长度=256bit)");
        } catch (Exception e) {
            throw new BizException(500, "CryptoService 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 加密给定明文。
     *
     * @param plain 明文字符串，必填
     * @return Base64 编码的密文（含 IV 与认证标签），可直接落库
     * @throws BizException 密钥未配置或加密异常时
     */
    public String encrypt(String plain) {
        if (plain == null) {
            throw new BizException(400, "encrypt 入参不可为 null");
        }
        ensureKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + cipherText.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(cipherText, 0, out, IV_BYTES, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.error("[CryptoService] 加密失败", e);
            throw new BizException(500, "加密失败: " + e.getMessage());
        }
    }

    /**
     * 解密给定 Base64 密文。
     *
     * @param cipherBase64 {@link #encrypt} 输出的 Base64 字符串
     * @return 原始明文
     * @throws BizException 密文损坏 / 密钥不匹配 / 密钥未配置时
     */
    public String decrypt(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            throw new BizException(400, "decrypt 入参不可为空");
        }
        ensureKey();
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            if (all.length <= IV_BYTES) {
                throw new BizException(400, "密文长度异常");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CryptoService] 解密失败", e);
            throw new BizException(500, "解密失败（密钥不匹配或密文损坏）");
        }
    }

    /**
     * 校验密钥已初始化。
     *
     * @throws BizException 密钥未配置时
     */
    private void ensureKey() {
        if (secretKey == null) {
            throw new BizException(500, "agent.sql.crypto-key 未配置，无法执行加解密");
        }
    }
}
