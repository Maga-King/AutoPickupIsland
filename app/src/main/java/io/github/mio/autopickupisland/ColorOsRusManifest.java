package io.github.mio.autopickupisland;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** RUS network manifest, distinct from the version embedded inside an AIFluid XML. */
final class ColorOsRusManifest {
    static final String FILTER = "sys_aifluid_config_list";
    enum Kind { WAIT, NO_NEW, NO_TARGET, FULL, PATCH, UNSUPPORTED }
    final Kind kind;
    final JSONObject target;
    final int count;
    private ColorOsRusManifest(Kind kind, JSONObject target, int count) {
        this.kind = kind; this.target = target; this.count = count;
    }
    static ColorOsRusManifest inspect(JSONObject response) throws Exception {
        if (response == null) throw new IOException("RUS_EMPTY_MANIFEST");
        // RomUpdateFilterTask tests these before download. Neither means an XML was applied.
        String message = response.optString("msg");
        if ("wait to zip".equals(message)) return new ColorOsRusManifest(Kind.WAIT, null, 0);
        if ("no new version".equals(message)) return new ColorOsRusManifest(Kind.NO_NEW, null, 0);
        if (response.has("resultCode") && !"1".equals(response.optString("resultCode")))
            throw new IOException("RUS_SERVICE_REJECTED");
        JSONArray infos = response.optJSONArray("infos");
        if (infos == null) throw new IOException("RUS_MISSING_INFOS");
        if (infos.length() > 4096) throw new IOException("RUS_TOO_MANY_INFOS");
        JSONObject target = null;
        for (int i = 0; i < infos.length(); i++) {
            JSONObject info = infos.getJSONObject(i);
            if (!FILTER.equals(info.optString("code"))) continue;
            if (target != null) throw new IOException("RUS_DUPLICATE_TARGET");
            target = info;
        }
        if (target == null) return new ColorOsRusManifest(Kind.NO_TARGET, null, infos.length());
        String type = target.optString("type");
        if ("0".equals(type)) return new ColorOsRusManifest(Kind.PATCH, target, infos.length());
        if (!"1".equals(type) || !"1".equals(target.optString("isTxt")))
            return new ColorOsRusManifest(Kind.UNSUPPORTED, target, infos.length());
        String name = target.getString("fileName");
        if (name.isEmpty() || name.length() > 240 || name.contains("..") || name.startsWith("/")
                || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0)
            throw new IOException("RUS_INVALID_FILE_NAME");
        if (!target.optString("md5").matches("(?i)[0-9a-f]{32}")
                || !response.optString("zipMd5").matches("(?i)[0-9a-f]{32}"))
            throw new IOException("RUS_INVALID_CHECKSUM");
        if (!target.optString("version").matches("[0-9]{1,18}"))
            throw new IOException("RUS_INVALID_METADATA_VERSION");
        if (response.optString("url").isEmpty()) throw new IOException("RUS_MISSING_ARCHIVE_URL");
        return new ColorOsRusManifest(Kind.FULL, target, infos.length());
    }
    private ColorOsRusManifest() { throw new AssertionError(); }
}
