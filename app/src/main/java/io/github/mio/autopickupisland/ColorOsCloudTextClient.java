package io.github.mio.autopickupisland;

import android.os.Bundle;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** OEM request construction/KMS/result parser; bounded HTTPS shell, text only.
 * No original startCloudRequest header logging or unbounded ResponseBody.string().
 * The caller owns consent, exact rule eligibility and notification instance checks.
 */
final class ColorOsCloudTextClient {
    static final int MAX_TEXT = 40_000, MAX_BODY = 512_000, MAX_RESPONSE = 1_000_000;
    private static final String HOST = "personal-inference-cn.allawntech.com";
    private static final String PATH = "/flash_memory/api/v2/alg";

    static Bundle recognize(ClassLoader loader, String text, BooleanSupplier live) throws Exception {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) throw new IOException("TEXT_LIMIT");
        check(live);
        Class<?> cloud = loader.loadClass("com.oplus.aiunit.plugin.cloud.CloudRequest");
        Class<?> crypto = loader.loadClass("com.oplus.aiunit.plugin.cloud.KMSCryptUtil");
        Class<?> bodyType = loader.loadClass("okhttp3.RequestBody");
        Class<?> bufferType = loader.loadClass("okio.Buffer");
        Method bodyBuilder = method(cloud, "buildRequestBody", byte[].class, String.class, String.class);
        Method headerBuilder = method(cloud, "buildHeaderMap");
        Method urlBuilder = method(cloud, "buildRequestUrl", String.class, String.class, String.class);
        Method parser = method(cloud, "formatResult", String.class);
        Method decrypt = crypto.getMethod("decrypt", String.class);
        Object client = cloud.getConstructor().newInstance();
        // startCloudRequest in this fixed OEM APK passes empty extended params;
        // appName/appPath are not used there. Never send an image byte array.
        Object body = bodyBuilder.invoke(client, null, text, "");
        if (body == null) throw new IOException("EMPTY_BODY");
        long length = (Long) bodyType.getMethod("contentLength").invoke(body);
        if (length < 1 || length > MAX_BODY) throw new IOException("BODY_LIMIT");
        Object buffer = bufferType.getConstructor().newInstance();
        byte[] bytes;
        try {
            bodyType.getMethod("writeTo", loader.loadClass("okio.BufferedSink")).invoke(body, buffer);
            long size = (Long) bufferType.getMethod("size").invoke(buffer);
            if (size != length || size > MAX_BODY) throw new IOException("BODY_LIMIT");
            bytes = (byte[]) bufferType.getMethod("readByteArray").invoke(buffer);
        } finally { bufferType.getMethod("clear").invoke(buffer); }
        Object headers = headerBuilder.invoke(client);
        String location = (String) urlBuilder.invoke(client, "https://" + HOST, PATH, "");
        URL url = new URL(location);
        if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost()) || !PATH.equals(url.getPath())
                || url.getUserInfo() != null || url.getRef() != null || url.getPort() != -1) throw new IOException("ENDPOINT");
        check(live);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3_000); connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        if (!(headers instanceof Map<?, ?> map)) throw new IOException("HEADERS");
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) throw new IOException("HEADERS");
            connection.setRequestProperty(key, value);
        }
        connection.setRequestProperty("Content-Type", bodyType.getMethod("contentType").invoke(body).toString());
        connection.setRequestProperty("source", "AIFluid");
        connection.setRequestProperty("User-Agent", "okhttp/4.12.0");
        // HttpURLConnection has no separate write timeout. One request-scoped
        // deadline disconnects a stuck body write; cancelled in every exit path.
        java.util.Timer deadline = new java.util.Timer("MioCloudHttpDeadline", true);
        try {
            deadline.schedule(new java.util.TimerTask() {
                @Override public void run() { try { connection.disconnect(); } catch (Throwable ignored) { } }
            }, CloudNamePolicy.WINDOW_MS);
            try (OutputStream out = connection.getOutputStream()) { check(live); out.write(bytes); }
            check(live);
            if (connection.getResponseCode() != 200) throw new IOException("HTTP_NOT_OK");
            if (connection.getContentLengthLong() > MAX_RESPONSE) throw new IOException("RESPONSE_LIMIT");
            byte[] response;
            try (InputStream in = connection.getInputStream()) { response = bounded(in, MAX_RESPONSE, live); }
            String plain = (String) decrypt.invoke(null, StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(response)).toString());
            check(live);
            if (plain == null || plain.isEmpty() || plain.length() > MAX_RESPONSE) throw new IOException("DECRYPT_EMPTY_OR_LIMIT");
            Object result = parser.invoke(client, plain);
            if (!(result instanceof Bundle bundle)) throw new IOException("RESULT_TYPE");
            return bundle;
        } finally { deadline.cancel(); java.util.Arrays.fill(bytes, (byte) 0); connection.disconnect(); }
    }
    static byte[] bounded(InputStream in, int limit, BooleanSupplier live) throws IOException {
        check(live);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1;) {
            check(live);
            if (out.size() + n > limit) throw new IOException("RESPONSE_LIMIT");
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }
    private static void check(BooleanSupplier live) throws IOException {
        if (Thread.currentThread().isInterrupted() || live == null || !live.getAsBoolean()) throw new InterruptedIOException("CANCELLED_OR_EXPIRED");
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private ColorOsCloudTextClient() { }
}
