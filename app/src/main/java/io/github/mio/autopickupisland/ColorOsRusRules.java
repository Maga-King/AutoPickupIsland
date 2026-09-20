package io.github.mio.autopickupisland;

import android.content.Context;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.*;

/** Full XML packages only. Unverified patches never reach the active rule repository. */
final class ColorOsRusRules {
    static final String FILTER = "sys_aifluid_config_list";
    static final int MAX_ZIP = 8_000_000, MAX_XML = 500_000;
    // Enabled after actual phone retrieval of RUS metadata 2026071400 / XML 20260702,
    // archive + file MD5 verification and production XML parsing. TLS remains mandatory.
    // Only complete text packages are supported; unknown formats and patches stay closed.
    static boolean canApplyVerifiedCloudRules() { return true; }

    static String sync(Context context) {
        return sync(context, false);
    }

    static String sync(Context context, boolean fullCheck) {
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("同步已取消");
            Bundle base = context.getContentResolver().call(Constants.PROVIDER_URI, "config", null, null);
            if (base == null) throw new IOException("配置读取失败");
            long revision = base.getLong("rules_revision", 0);
            JSONObject response = ColorOsRusTransport.query(fullCheck ? "" : base.getString("rules_rus_md5", ""));
            ColorOsRusManifest manifest = ColorOsRusManifest.inspect(response);
            switch (manifest.kind) {
                case WAIT: return "官方正在打包；本次未取得 XML，按下次同步计划再查";
                case NO_NEW: return "官方回复 no new version；本次未取得 XML，保留当前规则";
                case NO_TARGET: return "查询成功：返回 " + manifest.count + " 条配置，无取餐 XML；当前规则保留"
                        + (fullCheck ? "（完整查询亦未返回目标）" : "。可用“重新校验完整 XML”复核缓存");
                case PATCH: return "官方返回取餐 XML 增量；补丁适配未验证，保留当前规则";
                case UNSUPPORTED: return "官方返回未知类型或非文本配置，保留当前 XML";
                default: break;
            }
            JSONObject target = manifest.target;
            if (!canApplyVerifiedCloudRules())
                return "已返回取餐 XML 完整包清单；目标文件适配待实测，暂不自动应用";
            String name = target.getString("fileName");
            byte[] zip = download(response.getString("url"));
            verifyMd5(zip, response.getString("zipMd5"));
            byte[] xmlBytes = extract(zip, name);
            verifyMd5(xmlBytes, target.getString("md5"));
            String xml = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(xmlBytes)).toString();
            long version = RuleRepository.checkedCloudVersion(xml);
            // RUS metadata version and XML root version are separate OEM layers.
            // AIFluid parses the XML root to choose its active config; do not equate them.
            Bundle extra = new Bundle(); extra.putString("xml", xml);
            extra.putString("md5", target.getString("md5"));
            extra.putString("metadata_version", target.getString("version"));
            extra.putLong("expected_revision", revision);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("同步已取消");
            Bundle saved = context.getContentResolver().call(Constants.PROVIDER_URI, "install_rus_rules", null, extra);
            if (saved == null || !saved.getBoolean("accepted", false))
                return "XML 未应用：版本回退、配置已变化或保存失败；保留当前规则";
            if (saved.getBoolean("unchanged", false)) return "已校验 RUS XML " + version + "，与当前云规则相同";
            return "已应用 RUS XML " + version;
        } catch (Exception error) {
            if ("RUS_HTTP_304".equals(error.getMessage()))
                return "HTTP 304：未取得 XML 正文，保留当前规则（未验证为最新）";
            return "XML 同步失败（" + error.getClass().getSimpleName() + "）："
                    + PickupEvent.truncate(PickupEvent.clean(error.getMessage()), 140) + "；旧规则保留";
        }
    }

    static void verifyMd5(byte[] bytes, String expected) throws Exception {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{32}")) throw new IOException("缺少合法 MD5");
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!expected.equalsIgnoreCase(hex.toString())) throw new IOException("MD5 校验失败");
    }

    static byte[] extract(byte[] archive, String target) throws IOException {
        byte[] found = null; int entries = 0, total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 128) throw new IOException("归档项目过多");
                // Read every entry with a limit: closeEntry alone could inflate an unbounded bomb.
                byte[] data = readBounded(zip, MAX_XML);
                total += data.length;
                if (total > MAX_ZIP) throw new IOException("归档解压超限");
                if (!entry.isDirectory() && target.equals(entry.getName())) {
                    if (found != null) throw new IOException("重复 XML 文件");
                    found = data;
                }
            }
        }
        if (found == null) throw new IOException("归档缺少目标 XML");
        return found;
    }

    static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit) throw new IOException("下载/解压大小超限");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] download(String location) throws Exception {
        // Original UpdateService.j upgrades HTTP locations before issuing the GET.
        if (location.startsWith("http://")) location = "https://" + location.substring(7);
        URL url = new URL(location); String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !(host.endsWith(".allawntech.com") || host.endsWith(".coloros.com") || host.endsWith(".heytapdl.com")))
            throw new IOException("未验证的规则下载域名");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("规则包 HTTP 非 200");
            try (InputStream in = connection.getInputStream()) { return readBounded(in, MAX_ZIP); }
        } finally { connection.disconnect(); }
    }
}
