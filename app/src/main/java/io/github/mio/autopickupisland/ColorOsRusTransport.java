package io.github.mio.autopickupisland;

import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** RUS v4 transport transcribed from original smali. No device identifiers or page data. */
final class ColorOsRusTransport {
    // RomUpdate utils/g.j: IdProviderImpl.getGUID() failure returns 64 ASCII zeroes.
    // Use only that OEM fallback, never query a Xiaomi GUID, IMEI, serial or Android ID.
    static final String FALLBACK_GUID = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA6iM84sJ+EAESXEqGC52JVDqPy/glHpLtHxYzkZeftwynUGQVZtter4+VHAAj6MkLk30l1owq0UP9WymRWLp7GssgM2KfKs1glC8pxZ7Z1iDXZMhqlsZWZMjQOTMXRjMomYGoeV31H1d5Ox698sHyAqR3p8zdWncb8RBSXUTo//AGX+LzKG1j4aBcB/a5sa2B0kLKyEf8APKxvXWJGDG0mzeZDPAvzeMH4Xse1/bbgqpuuddxTwjzN/cFNR8rlu9RfCYItiJ3MP2PKJj16Qq0X1xiMVcSqZmgnNt/DSmDSNwN612aUCz+cRLTvHlbMssVdoCAGBApyMMj/BFG3o9JyQIDAQAB";
    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static JSONObject requestJson(long now, String installedMd5) throws Exception {
        if (installedMd5 == null || !installedMd5.matches("(?i)([0-9a-f]{32})?"))
            throw new IllegalArgumentException("Invalid installed rule checksum");
        return new JSONObject().put("version", "4").put("decentra", "1")
                .put("msgCode", "").put("mode", "0").put("type", "0")
                .put("productName", "PJZ110").put("androidVersion", "Android16")
                .put("osVersion", "ColorOS16.1.0").put("romVersion", "PJZ110_16.0.10.501(CN01)")
                .put("otaVersion", "PJZ110_11.C.93_1930_202608031342")
                .put("language", "zh-CN").put("nvCarrier", "10010111")
                .put("operator", "").put("sotaVersion", "").put("pushVersion", "1")
                .put("registrationId", "UNKNOWN").put("time", now).put("imei", FALLBACK_GUID)
                .put("infos", new JSONArray().put(new JSONObject()
                        .put("code", "sys_aifluid_config_list").put("md5", installedMd5)));
    }
    static String fallbackDeviceId() throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes(FALLBACK_GUID)))
            out.append(String.format(Locale.ROOT, "%02X", b & 255));
        return out.toString();
    }
    static JSONObject query() throws Exception {
        // No verified RUS cache yet. Do not advertise a manual/imported XML as server-installed.
        return query("");
    }
    static JSONObject query(String installedMd5) throws Exception {
        JSONObject request = requestJson(System.currentTimeMillis(), installedMd5);
        KeyGenerator gen = KeyGenerator.getInstance("AES"); gen.init(256);
        SecretKey key = gen.generateKey();
        byte[] iv = new byte[16]; new SecureRandom().nextBytes(iv);
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        JSONObject params = new JSONObject().put("cipher", b64(aes.doFinal(bytes(request.toString()))))
                .put("iv", b64(iv));
        Cipher rsa = Cipher.getInstance("RSA/None/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))));
        JSONObject header = new JSONObject().put("TEST_SCENE_1", new JSONObject()
                .put("protectedKey", b64(rsa.doFinal(bytes(b64(key.getEncoded())))))
                .put("version", Long.toString(System.currentTimeMillis() + 86400000L))
                .put("negotiationVersion", "1602488677852"));
        byte[] body = bytes(new JSONObject().put("params", params.toString()).put("version", "4").toString());
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://rus-service-cn.allawntech.com/post/Query_Update").openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(25000); c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("mode", "client_auto"); c.setRequestProperty("protectedKey", header.toString());
        c.setRequestProperty("User-Agent", "PJZ110/16/ColorOS16.1.0/16.0.12");
        c.setRequestProperty("deviceId", fallbackDeviceId());
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int status = c.getResponseCode();
            
            if (status != 200) throw new IOException("RUS_HTTP_" + status);
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (stream == null) throw new IOException("RUS_EMPTY_BODY");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 2000000) throw new IOException("Response limit");
                    out.write(buffer, 0, n);
                }
            }
            JSONObject response = new JSONObject(out.toString("UTF-8"));
            JSONObject encrypted = response.optJSONObject("resps");
            if (encrypted == null) throw new IOException("RUS_MISSING_ENVELOPE");
            return decryptResponse(encrypted, key);
        } finally { c.disconnect(); }
    }

    static JSONObject decryptResponse(JSONObject encrypted, SecretKey key) throws Exception {
        byte[] iv = Base64.getDecoder().decode(encrypted.getString("iv"));
        if (iv.length != 16) throw new IOException("RUS_INVALID_IV");
        String encoded = encrypted.getString("cipher");
        if (encoded.length() > 2_000_000) throw new IOException("RUS_RESPONSE_LIMIT");
        byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length == 0) throw new IOException("RUS_EMPTY_CIPHER");
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        String plain = StandardCharsets.UTF_8.newDecoder().decode(
                java.nio.ByteBuffer.wrap(aes.doFinal(data))).toString();
        return new JSONObject(plain);
    }
}
