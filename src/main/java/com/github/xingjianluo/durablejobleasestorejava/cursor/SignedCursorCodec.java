package com.github.xingjianluo.durablejobleasestorejava.cursor;

import com.github.xingjianluo.durablejobleasestorejava.config.LeaseProperties;
import com.github.xingjianluo.durablejobleasestorejava.error.ApiException;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * 不透明游标 = base64url("anchorId|issuedAt") + "." + base64url(HMAC-SHA256(payload, secret))。
 * <ul>
 *   <li>payload 被改动 / 签名错误 / 格式非法 统一判为 CURSOR_INVALID（游标被篡改）。</li>
 *   <li>签名合法但签发时间超过 cursorTtl，判为 CURSOR_EXPIRED（游标过期）。</li>
 * </ul>
 * 锚点记录是否仍存在由存储层负责，错误为 CURSOR_ANCHOR_DELETED，三类可区分。
 */
@Component
public class SignedCursorCodec implements CursorCodec {

    private static final Logger log = LoggerFactory.getLogger(SignedCursorCodec.class);
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SEP = "|";

    private final LeaseProperties properties;
    private final Clock clock;

    public SignedCursorCodec(LeaseProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String encode(String anchorId) {
        long issuedAt = clock.millis();
        String payload = anchorId + SEP + issuedAt;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sigB64 = sign(payloadB64);
        String token = payloadB64 + "." + sigB64;
        log.debug("签发游标 anchorId={}, issuedAt={}, ttl={}s -> {}",
                anchorId, issuedAt, properties.getCursorTtl().toSeconds(), token);
        return token;
    }

    @Override
    public Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "游标为空");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            log.info("游标判定为非法：缺少 payload/signature 分段, token={}", token);
            throw new ApiException(ErrorCode.CURSOR_INVALID, "游标格式被破坏");
        }
        String payloadB64 = token.substring(0, dot);
        String sigB64 = token.substring(dot + 1);

        // 1) 签名校验：任何篡改（payload、签名）都在此失败
        String expectedSig = sign(payloadB64);
        if (!constantTimeEquals(expectedSig, sigB64)) {
            log.info("游标判定为被篡改：HMAC 不匹配, token={}", token);
            throw new ApiException(ErrorCode.CURSOR_INVALID, "游标签名校验失败（可能被篡改）");
        }

        String payload;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(payloadB64);
            payload = new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.info("游标判定为非法：payload 不是合法 base64, token={}", token);
            throw new ApiException(ErrorCode.CURSOR_INVALID, "游标 payload 解码失败");
        }

        int sep = payload.lastIndexOf(SEP);
        if (sep <= 0 || sep == payload.length() - 1) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "游标内容结构被破坏");
        }
        String anchorId = payload.substring(0, sep);
        long issuedAt;
        try {
            issuedAt = Long.parseLong(payload.substring(sep + 1));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.CURSOR_INVALID, "游标时间戳被破坏");
        }

        // 2) 过期校验（签名合法之后）
        long ageMillis = clock.millis() - issuedAt;
        long ttlMillis = properties.getCursorTtl().toMillis();
        if (ageMillis > ttlMillis) {
            log.info("游标判定为过期：anchorId={}, ageMs={}, ttlMs={}", anchorId, ageMillis, ttlMillis);
            throw new ApiException(ErrorCode.CURSOR_EXPIRED,
                    "游标已过期 " + ageMillis + "ms（上限 " + ttlMillis + "ms）");
        }
        return new Cursor(anchorId, issuedAt);
    }

    private String sign(String payloadB64) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    properties.getCursorSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] sig = mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
