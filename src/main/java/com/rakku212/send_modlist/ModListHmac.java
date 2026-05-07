package com.rakku212.send_modlist;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

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
            byte[] digest = mac.doFinal(message);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int value = b & 0xFF;
                if (value < 0x10) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
