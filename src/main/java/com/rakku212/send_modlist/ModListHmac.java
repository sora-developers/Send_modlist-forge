package com.rakku212.send_modlist;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class ModListHmac {
    private static final String HMAC_SHA256 = "HmacSHA256";

    public static byte[] utf8SignedPayload(byte[] sessionKey, String jsonBodyUtf8) {
        String hex = hmacSha256Hex(sessionKey, jsonBodyUtf8.getBytes(StandardCharsets.UTF_8));
        String signed = hex + '\n' + jsonBodyUtf8;
        return signed.getBytes(StandardCharsets.UTF_8);
    }

    public static String hmacSha256Hex(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
