package com.github.xingjianluo.durablejobleasestorejava.cursor;

/**
 * 游标编解码：负责签名防篡改与过期校验。
 */
public interface CursorCodec {

    /** 将锚点编码为不透明游标字符串。 */
    String encode(String anchorId);

    /**
     * 解码并校验游标。
     * 非法/被篡改 -> CURSOR_INVALID；过期 -> CURSOR_EXPIRED。
     */
    Cursor decode(String token);
}
