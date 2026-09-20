package io.github.mio.autopickupisland;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Explicit/manual or daily-job reader for the OEM pickup-applet configuration channel. */
final class ColorOsCloudRules {
    private static final String ENDPOINT =
            "https://iwisdom.apps.coloros.com/wisdom/getRuleManage";
    private static final String PICKUP_SCENE = "AIFluidWxAppletList";
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int MAX_WHITELIST_ENTRIES = 500;

    static final class SyncResult {
        final boolean success;
        final int count;
        final String message;

        SyncResult(boolean success, int count, String message) {
            this.success = success;
            this.count = count;
            this.message = message;
        }
    }

    static SyncResult sync(Context context) {
        HttpURLConnection connection = null;
        try {
            Bundle base = context.getContentResolver().call(Constants.PROVIDER_URI, "config", null, null);
            if (base == null || !base.containsKey("rules_revision")) throw new IllegalStateException("配置读取失败");
            long revision = base.getLong("rules_revision");
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("同步已取消");
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            // Matches the only header added by ColorOS' HeaderInterceptor.
            connection.setRequestProperty("appVersion", "null:" + Build.VERSION.SDK_INT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "okhttp/4.12.0");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return new SyncResult(false, 0, "同步失败：HTTP " + status);
            }
            InputStream raw = connection.getInputStream();
            String encoding = connection.getContentEncoding();
            try (InputStream input = encoding != null && encoding.toLowerCase(Locale.ROOT)
                    .contains("gzip") ? new GZIPInputStream(raw) : raw) {
                JSONObject response = new JSONObject(readUtf8(input));
                if (response.optInt("code", -1) != 200) {
                    return new SyncResult(false, 0,
                            "同步失败：" + PickupEvent.clean(response.optString("msg")));
                }
                JSONArray all = response.optJSONArray("data");
                if (all == null) throw new IllegalArgumentException("missing data");
                JSONArray pickup = null;
                long sourceUpdateTime = 0L;
                for (int i = 0; i < all.length(); i++) {
                    JSONObject rule = all.optJSONObject(i);
                    if (rule == null || !PICKUP_SCENE.equals(rule.optString("scene"))) continue;
                    Object ext = rule.opt("ext");
                    pickup = ext instanceof JSONArray array ? array
                            : new JSONArray(String.valueOf(ext));
                    sourceUpdateTime = Math.max(0L, rule.optLong("updateTime", 0L));
                    break;
                }
                String canonical = canonicalize(pickup == null ? "" : pickup.toString());
                Bundle extras = new Bundle();
                extras.putString("whitelist", canonical);
                extras.putString("request_id", response.optString("requestId", ""));
                extras.putLong("source_update_time", sourceUpdateTime);
                extras.putLong("expected_revision", revision);
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("同步已取消");
                Bundle stored = context.getContentResolver().call(
                        Constants.PROVIDER_URI, "install_cloud_rules", null, extras);
                if (stored == null || !stored.getBoolean("accepted", false)) {
                    return new SyncResult(false, 0, "名单未应用：配置已变化、云版本回退或保存失败；当前配置保留");
                }
                int count = stored.getInt("count", count(canonical));
                return new SyncResult(true, count,
                        (stored.getBoolean("unchanged", false) ? "已校验，名单未变化：" : "已应用 ColorOS 小程序白名单：") + count + " 个");
            }
        } catch (Throwable error) {
            String detail = PickupEvent.clean(error.getMessage());
            if (detail.isEmpty()) detail = error.getClass().getSimpleName();
            return new SyncResult(false, 0, "同步失败：" + detail + "；旧缓存已保留");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    enum UpdateDecision { CONFIG_CHANGED, ROLLBACK, UNCHANGED, APPLY }
    static UpdateDecision decideUpdate(long expectedRevision, long revision, long incomingTime,
            long currentTime, String incoming, String current) {
        if (expectedRevision < 0 || expectedRevision != revision) return UpdateDecision.CONFIG_CHANGED;
        if (incomingTime > 0 && currentTime > incomingTime) return UpdateDecision.ROLLBACK;
        return incoming.equals(current) ? UpdateDecision.UNCHANGED : UpdateDecision.APPLY;
    }

    static String canonicalize(String raw) throws Exception {
        if (raw == null || raw.length() < 2 || raw.length() > 300_000) {
            throw new IllegalArgumentException("whitelist size");
        }
        JSONArray source = new JSONArray(raw);
        if (source.length() < 1 || source.length() > MAX_WHITELIST_ENTRIES) {
            throw new IllegalArgumentException("whitelist count");
        }
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            String name = PickupEvent.truncate(PickupEvent.clean(item.optString("name")), 80);
            String id = PickupEvent.clean(item.optString("id")).toLowerCase(Locale.ROOT);
            String originalId = PickupEvent.clean(item.optString("origId"))
                    .toLowerCase(Locale.ROOT);
            if (name.isEmpty() || !id.matches("wx[a-z0-9]{8,40}")
                    || !originalId.matches("gh_[a-z0-9]{6,40}") || !seen.add(id)) continue;
            JSONObject clean = new JSONObject();
            clean.put("name", name);
            clean.put("id", id);
            clean.put("origId", originalId);
            clean.put("disabled", item.optBoolean("disabled", false));
            result.put(clean);
        }
        if (result.length() < 1) throw new IllegalArgumentException("no valid pickup applets");
        return result.toString();
    }

    static int count(String raw) {
        try {
            return raw == null || raw.isEmpty() ? 0 : new JSONArray(raw).length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32_768];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
            if (output.size() > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("response too large");
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private ColorOsCloudRules() {
    }
}
