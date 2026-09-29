package com.github.xingjianluo.durablejobleasestorejava.cursor;

import com.github.xingjianluo.durablejobleasestorejava.config.JobProperties;
import com.github.xingjianluo.durablejobleasestorejava.error.ErrorCode;
import com.github.xingjianluo.durablejobleasestorejava.error.JobApiException;
import com.github.xingjianluo.durablejobleasestorejava.time.TimeSource;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 不透明游标：base64url(seq.createdAt.issuedAt) + "." + base64url(HMAC-SHA256(payload))。
 * <ul>
 *   <li>格式错误/签名不匹配 → CURSOR_INVALID（被篡改）</li>
 *   <li>issuedAt 超过 TTL → CURSOR_EXPIRED（过期）</li>
 *   <li>能解码但锚点记录已删除 → 由存储层判定 CURSOR_ENTRY_DELETED</li>
 * </ul>
 */
@Component
public class CursorService {

    public record Anchor(long seq, long createdAtMillis) {
    }

    private final TimeSource timeSource;
    private final JobProperties props;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public CursorService(TimeSource timeSource, JobProperties props) {
        this.timeSource = timeSource;
        this.props = props;
    }

    public String encode(long seq, long createdAtMillis) {
        String payload = seq + "." + createdAtMillis + "." + timeSource.nowMillis();
        return encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(sign(payload));
    }

    public Anchor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标为空");
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标格式错误");
        }
        String payloadB64 = token.substring(0, dot);
        String sigB64 = token.substring(dot + 1);

        String payload;
        byte[] expectedSig;
        try {
            payload = new String(decoder.decode(payloadB64), StandardCharsets.UTF_8);
            expectedSig = sign(payload);
        } catch (IllegalArgumentException e) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标 Base64 解码失败（疑似被篡改）");
        }

        byte[] providedSig;
        try {
            providedSig = decoder.decode(sigB64);
        } catch (IllegalArgumentException e) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标签名 Base64 解码失败（疑似被篡改）");
        }
        if (!java.security.MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标签名校验失败（疑似被篡改）");
        }

        String[] parts = payload.split("\\.", -1);
        if (parts.length != 3) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标负载字段数量错误");
        }
        try {
            long seq = Long.parseLong(parts[0]);
            long createdAt = Long.parseLong(parts[1]);
            long issuedAt = Long.parseLong(parts[2]);
            long age = timeSource.nowMillis() - issuedAt;
            if (age < 0 || age > props.getCursorTtlMillis()) {
                throw new JobApiException(ErrorCode.CURSOR_EXPIRED,
                        "游标已过期：签发于 " + issuedAt + "，TTL=" + props.getCursorTtlMillis() + "ms");
            }
            return new Anchor(seq, createdAt);
        } catch (NumberFormatException e) {
            throw new JobApiException(ErrorCode.CURSOR_INVALID, "游标负载包含非数字字段（疑似被篡改）");
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getCursorSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 HMAC-SHA256", e);
        }
    }
}
