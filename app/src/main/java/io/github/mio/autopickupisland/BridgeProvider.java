package io.github.mio.autopickupisland;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class BridgeProvider extends ContentProvider {
    private static final String PREFS = "bridge_state";
    private static final Set<String> EVENT_SENDERS = new HashSet<>(Arrays.asList(
            Constants.MODULE_PACKAGE,
            Constants.PKG_WECHAT,
            Constants.PKG_ALIPAY,
            "com.lucky.luckyclient",
            "com.heyteago",
            "com.mxbc.mxsa",
            "com.yek.android.kfc.activitys",
            "com.mcdonalds.gma.cn",
            "com.starbucks.cn",
            Constants.PKG_TEST_FIXTURE
    ));
    private static final Set<String> SYSTEM_CLIENTS = new HashSet<>(Arrays.asList(
            Constants.MODULE_PACKAGE, Constants.PKG_AICR, Constants.PKG_VOICE_ASSIST));

    private SharedPreferences prefs;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method == null) return denied("missing method");
        return switch (method) {
            case "publish" -> publish(extras);
            case "system_publish" -> systemPublish(extras);
            case "peek" -> peek(extras);
            case "ack" -> acknowledge(extras);
            case "heartbeat" -> heartbeat();
            case "hook_health" -> hookHealth(extras);
            case "config" -> config();
            case "set_model" -> setModel(extras);
            case "set_fallbacks" -> setFallbacks(extras);
            case "rules" -> rules();
            case "install_rules" -> installRules(extras);
            case "install_rus_rules" -> installRusRules(extras);
            case "cloud_rules" -> cloudRules();
            case "install_cloud_rules" -> installCloudRules(extras);
            case "reset_defaults" -> resetDefaults();
            case "status" -> status();
            case "clear" -> clear();
            case "fixture_catalog" -> fixtureCatalog();
            default -> denied("unknown method");
        };
    }

    private Bundle hookHealth(Bundle extras) {
        if (extras == null) return denied("missing health");
        String role = extras.getString("role", "");
        String owner = switch (role) {
            case "voice" -> Constants.PKG_VOICE_ASSIST;
            case "aicr" -> Constants.PKG_AICR;
            case "assist" -> Constants.PKG_PRIVILEGED_ASSIST;
            case "loader" -> "android";
            default -> "";
        };
        if (owner.isEmpty() || !callingUidOwns(owner)) return denied("health uid");
        String data = extras.getString("data", "");
        if (data.length() > 6000) return denied("health length");
        try {
            new org.json.JSONObject(data);
            prefs.edit().putString("hook_health_" + role, data).apply();
            Bundle result = new Bundle(); result.putBoolean("accepted", true); return result;
        } catch (Exception e) { return denied("health format"); }
    }

    private Bundle publish(Bundle extras) {
        if (!callerAllowed(EVENT_SENDERS)) return denied("sender uid");
        PickupEvent event = PickupEvent.fromBundle(extras);
        if (event == null) return denied("invalid event");
        if (Binder.getCallingUid() != Process.myUid()
                && !callingUidOwns(event.sourcePackage)) {
            return denied("package mismatch");
        }

        String json = event.toJson().toString();
        String key = event.navigationOnly ? "last_nav" : "last_event";
        String id = event.id();
        boolean duplicate = id.equals(prefs.getString(key + "_id", ""));
        prefs.edit()
                .putString(key, json)
                .putString(key + "_id", id)
                .putLong(key + "_time", event.timestamp)
                .putLong("last_event_time", event.navigationOnly
                        ? prefs.getLong("last_event_time", 0L) : event.timestamp)
                .putString("last_source", event.sourcePackage)
                .apply();

        if (!event.navigationOnly && !duplicate) notifyAicr(event);
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        result.putBoolean("duplicate", duplicate);
        result.putBoolean("aicr_fresh",
                System.currentTimeMillis() - prefs.getLong("aicr_heartbeat", 0L) < 120_000L);
        return result;
    }

    private Bundle systemPublish(Bundle extras) {
        if (!callerAllowed(Set.of(Constants.PKG_AICR))) return denied("system bridge uid");
        PickupEvent event = PickupEvent.fromBundle(extras);
        if (event == null) return denied("invalid event");
        String key = event.navigationOnly ? "last_nav" : "last_event";
        prefs.edit()
                .putString(key, event.toJson().toString())
                .putString(key + "_id", event.id())
                .putLong(key + "_time", event.timestamp)
                .putLong("last_event_time", event.navigationOnly
                        ? prefs.getLong("last_event_time", 0L) : event.timestamp)
                .putString("last_source", event.sourcePackage)
                .apply();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle peek(Bundle extras) {
        if (!callerAllowed(SYSTEM_CLIENTS)) return denied("client uid");
        boolean navigation = extras != null && extras.getBoolean("navigation", false);
        String raw = prefs.getString(navigation ? "last_nav" : "last_event", "");
        PickupEvent event = PickupEvent.fromJson(raw);
        return event == null ? new Bundle() : event.toBundle();
    }

    private Bundle acknowledge(Bundle extras) {
        if (!callerAllowed(SYSTEM_CLIENTS)) return denied("client uid");
        PickupEvent event = PickupEvent.fromBundle(extras);
        SharedPreferences.Editor edit = prefs.edit()
                .putLong("last_ack_time", System.currentTimeMillis())
                .putString("last_state", extras == null ? "" : extras.getString("state", ""))
                .putString("last_code", extras == null ? "" : extras.getString("code", ""))
                .putString("last_detail", extras == null ? "" : extras.getString("detail", ""));
        if (event != null) edit.putString("last_ack_event", event.id());
        edit.apply();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle heartbeat() {
        if (!callerAllowed(SYSTEM_CLIENTS)) return denied("client uid");
        prefs.edit().putLong("aicr_heartbeat", System.currentTimeMillis()).apply();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle config() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID
                && !callerAllowed(SYSTEM_CLIENTS)) return denied("client uid");
        Bundle result = new Bundle();
        result.putLong("rules_revision", prefs.getLong("rules_revision", 0L));
        result.putBoolean("cloud_text_name", prefs.getBoolean("cloud_text_name", false));
        result.putString("rules_rus_md5", "rus".equals(prefs.getString("rules_source", ""))
                ? prefs.getString("rules_rus_md5", "") : "");
        result.putBoolean("model_enabled", prefs.getBoolean("model_enabled", true));
        result.putBoolean("local_image_ocr", prefs.getBoolean("local_image_ocr", false));
        result.putBoolean("remote_image_recognition",
                prefs.getBoolean("remote_image_recognition", false));
        return result;
    }

    private Bundle fixtureCatalog() {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE, Constants.PKG_TEST_FIXTURE))) return denied("fixture uid");
        try {
            Bundle result = new Bundle();
            result.putString("cases", FixtureCatalog.json(providerContext()));
            result.putString("cloud", prefs.getString("cloud_applet_whitelist", "[]"));
            return result;
        } catch (Exception error) { return denied("catalog unavailable"); }
    }

    private synchronized Bundle setModel(Bundle extras) {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        boolean enabled = extras == null || extras.getBoolean("enabled", true);
        prefs.edit().putBoolean("model_enabled", enabled).apply();
        configurationChanged();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private synchronized Bundle setFallbacks(Bundle extras) {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        boolean local = extras != null && extras.getBoolean("local_image_ocr", false);
        boolean remote = extras != null
                && extras.getBoolean("remote_image_recognition", false);
        boolean cloud = extras == null ? prefs.getBoolean("cloud_text_name", false)
                : extras.getBoolean("cloud_text_name", prefs.getBoolean("cloud_text_name", false));
        if (cloud && !prefs.getBoolean("cloud_text_name", false)
                && (extras == null || !extras.getBoolean("cloud_text_consent", false))) return denied("cloud consent required");
        if (!prefs.edit()
                .putBoolean("local_image_ocr", local)
                .putBoolean("remote_image_recognition", remote)
                .putBoolean("cloud_text_name", cloud)
                .commit()) return denied("save failed");
        configurationChanged();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle rules() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID
                && !callerAllowed(union(EVENT_SENDERS, SYSTEM_CLIENTS))) return denied("client uid");
        Bundle result = new Bundle();
        result.putString("xml", prefs.getString("rules_overlay", ""));
        return result;
    }

    private synchronized Bundle installRules(Bundle extras) {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        String xml = extras == null ? "" : extras.getString("xml", "");
        if (!RuleRepository.looksLikeRuleXml(xml)) return denied("invalid xml");
        prefs.edit().putString("rules_overlay", xml).putString("rules_source", "import")
                .remove("rules_rus_md5").remove("rules_rus_metadata_version")
                .putLong("rules_installed_at", System.currentTimeMillis()).apply();
        configurationChanged();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private synchronized Bundle installRusRules(Bundle extras) {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        if (!ColorOsRusRules.canApplyVerifiedCloudRules()) return denied("RUS trust path not verified");
        try {
            if (extras == null || extras.getLong("expected_revision", -1) != prefs.getLong("rules_revision", 0))
                return denied("configuration changed");
            String xml = extras.getString("xml", "");
            String md5 = extras.getString("md5", "");
            String metadataVersion = extras.getString("metadata_version", "");
            if (!metadataVersion.matches("[0-9]{1,18}")) return denied("invalid RUS metadata");
            ColorOsRusRules.verifyMd5(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), md5);
            long incoming = RuleRepository.checkedCloudVersion(xml);
            String existing = prefs.getString("rules_overlay", "");
            long current;
            if (existing.isEmpty()) {
                try (var in = providerContext().getAssets().open(Constants.RULE_ASSET)) {
                    current = RuleRepository.checkedCloudVersion(new String(ColorOsRusRules.readBounded(in, 500_000),
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            } else current = RuleRepository.checkedCloudVersion(existing);
            if (incoming < current) return denied("version rollback");
            if (xml.equals(existing) && "rus".equals(prefs.getString("rules_source", ""))
                    && md5.equalsIgnoreCase(prefs.getString("rules_rus_md5", ""))) {
                Bundle result = new Bundle(); result.putBoolean("accepted", true);
                result.putBoolean("unchanged", true); return result;
            }
            if (!prefs.edit().putString("rules_overlay", xml).putString("rules_source", "rus")
                    .putString("rules_rus_md5", md5.toLowerCase(java.util.Locale.ROOT))
                    .putString("rules_rus_metadata_version", metadataVersion)
                    .putLong("rules_installed_at", System.currentTimeMillis())
                    .putLong("rules_revision", Math.max(System.currentTimeMillis(), prefs.getLong("rules_revision", 0) + 1))
                    .commit()) return denied("save failed");
            configurationChanged();
            Bundle result = new Bundle(); result.putBoolean("accepted", true); return result;
        } catch (Exception error) { return denied("invalid rus xml"); }
    }

    private Bundle cloudRules() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID
                && !callerAllowed(union(EVENT_SENDERS, SYSTEM_CLIENTS))) return denied("client uid");
        Bundle result = new Bundle();
        result.putString("whitelist", prefs.getString("cloud_applet_whitelist", ""));
        result.putString("request_id", prefs.getString("cloud_request_id", ""));
        result.putLong("updated_at", prefs.getLong("cloud_updated_at", 0L));
        result.putLong("source_update_time", prefs.getLong("cloud_source_update_time", 0L));
        return result;
    }

    private synchronized Bundle installCloudRules(Bundle extras) {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        try {
            String raw = extras == null ? "" : extras.getString("whitelist", "");
            String canonical = ColorOsCloudRules.canonicalize(raw);
            int count = ColorOsCloudRules.count(canonical);
            String requestId = extras == null ? "" : PickupEvent.truncate(
                    PickupEvent.clean(extras.getString("request_id", "")), 128);
            long sourceUpdateTime = extras == null ? 0L
                    : Math.max(0L, extras.getLong("source_update_time", 0L));
            var decision = ColorOsCloudRules.decideUpdate(extras == null ? -1 : extras.getLong("expected_revision", -1),
                    prefs.getLong("rules_revision", 0), sourceUpdateTime, prefs.getLong("cloud_source_update_time", 0),
                    canonical, prefs.getString("cloud_applet_whitelist", ""));
            if (decision == ColorOsCloudRules.UpdateDecision.CONFIG_CHANGED) return denied("configuration changed");
            if (decision == ColorOsCloudRules.UpdateDecision.ROLLBACK) return denied("version rollback");
            if (!prefs.edit()
                    .putString("cloud_applet_whitelist", canonical)
                    .putString("cloud_request_id", requestId)
                    .putLong("cloud_updated_at", System.currentTimeMillis())
                    .putLong("cloud_source_update_time", sourceUpdateTime)
                    .commit()) return denied("save failed");
            boolean unchanged = decision == ColorOsCloudRules.UpdateDecision.UNCHANGED;
            if (!unchanged) configurationChanged();
            Bundle result = new Bundle();
            result.putBoolean("accepted", true);
            result.putInt("count", count);
            result.putBoolean("unchanged", unchanged);
            return result;
        } catch (Exception error) {
            return denied("invalid cloud rules");
        }
    }

    private synchronized Bundle resetDefaults() {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        prefs.edit()
                .putBoolean("model_enabled", true)
                .putBoolean("local_image_ocr", false)
                .putBoolean("remote_image_recognition", false)
                .putBoolean("cloud_text_name", false)
                .remove("rules_overlay")
                .remove("rules_source")
                .remove("rules_installed_at")
                .remove("rules_rus_md5")
                .remove("rules_rus_metadata_version")
                .remove("cloud_applet_whitelist")
                .remove("cloud_request_id")
                .remove("cloud_updated_at")
                .remove("cloud_source_update_time")
                .apply();
        configurationChanged();
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle status() {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        Bundle result = new Bundle();
        result.putString("state", prefs.getString("last_state", "尚无结果"));
        for (String role : new String[]{"voice", "aicr", "assist", "loader"})
            result.putString("hook_health_" + role, prefs.getString("hook_health_" + role, ""));
        result.putString("code", prefs.getString("last_code", ""));
        result.putString("detail", prefs.getString("last_detail", ""));
        result.putString("source", prefs.getString("last_source", ""));
        result.putLong("event_time", prefs.getLong("last_event_time", 0L));
        result.putLong("ack_time", prefs.getLong("last_ack_time", 0L));
        result.putLong("heartbeat", prefs.getLong("aicr_heartbeat", 0L));
        result.putBoolean("model_enabled", prefs.getBoolean("model_enabled", true));
        result.putBoolean("cloud_text_name", prefs.getBoolean("cloud_text_name", false));
        result.putBoolean("local_image_ocr", prefs.getBoolean("local_image_ocr", false));
        result.putBoolean("remote_image_recognition",
                prefs.getBoolean("remote_image_recognition", false));
        result.putBoolean("overlay", !prefs.getString("rules_overlay", "").isEmpty());
        result.putString("rules_source", prefs.getString("rules_source", ""));
        result.putInt("cloud_rule_count",
                ColorOsCloudRules.count(prefs.getString("cloud_applet_whitelist", "")));
        result.putLong("cloud_updated_at", prefs.getLong("cloud_updated_at", 0L));
        return result;
    }

    private Bundle clear() {
        if (!callerAllowed(Set.of(Constants.MODULE_PACKAGE))) return denied("client uid");
        // Diagnostics are not configuration: preserve cloud/imported policy and its revision.
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("last_") || "aicr_heartbeat".equals(key)) edit.remove(key);
        }
        edit.apply();
        signalProcesses(Constants.ACTION_CLEAR_STATE);
        Bundle result = new Bundle();
        result.putBoolean("accepted", true);
        return result;
    }

    private void configurationChanged() {
        long next = Math.max(System.currentTimeMillis(), prefs.getLong("rules_revision", 0L) + 1L);
        prefs.edit().putLong("rules_revision", next).apply();
        RuleRepository.invalidate();
        signalProcesses(Constants.ACTION_CONFIG_CHANGED);
        try {
            if (getContext() != null) getContext().getContentResolver()
                    .notifyChange(Constants.PROVIDER_URI, null);
        } catch (RuntimeException ignored) { }
    }

    private void signalProcesses(String action) {
        if (getContext() == null) return;
        long identity = Binder.clearCallingIdentity();
        try {
            for (ComponentName receiver : new ComponentName[]{
                    new ComponentName(Constants.PKG_AICR, Constants.AICR_WAKE_RECEIVER),
                    new ComponentName(Constants.PKG_PRIVILEGED_ASSIST, Constants.PRIVILEGED_ASSIST_RECEIVER)}) {
                try {
                    Intent intent = new Intent(action).setComponent(receiver)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    Bundle options = android.app.BroadcastOptions.makeBasic()
                            .setShareIdentityEnabled(true).toBundle();
                    providerContext().sendBroadcast(intent, null, options);
                } catch (Exception ignored) { }
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private void notifyAicr(PickupEvent event) {
        Context context = getContext();
        if (context == null) return;
        Intent data = new Intent(Constants.ACTION_EVENT)
                .setComponent(new ComponentName(Constants.PKG_AICR,
                        Constants.AICR_WAKE_RECEIVER))
                .putExtras(event.toBundle())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            context.sendBroadcast(data, null, android.app.BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true).toBundle());
        } catch (Exception ignored) {
        }
    }

    private boolean callerAllowed(Set<String> allowed) {
        if (Binder.getCallingUid() == Process.myUid()) return allowed.contains(Constants.MODULE_PACKAGE);
        String[] packages = providerContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String pkg : packages) if (allowed.contains(pkg)) return true;
        return false;
    }

    private boolean callingUidOwns(String packageName) {
        String[] packages = providerContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String candidate : packages) if (candidate.equals(packageName)) return true;
        return false;
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("provider detached");
        return context;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        HashSet<String> result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    private static Bundle denied(String reason) {
        Bundle result = new Bundle();
        result.putBoolean("accepted", false);
        result.putString("reason", reason);
        return result;
    }

    @Override
    public String getType(Uri uri) {
        return assetName(uri) == null ? null : "image/webp";
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        String name = assetName(uri);
        if (name == null || !("r".equals(mode) || mode == null)) {
            throw new FileNotFoundException("invalid asset request");
        }
        if (!assetCallerAllowed()) throw new FileNotFoundException("caller rejected");
        try {
            return providerContext().getAssets().openFd(Constants.IMAGE_ROOT + name);
        } catch (IOException | RuntimeException error) {
            FileNotFoundException failure = new FileNotFoundException("asset unavailable");
            failure.initCause(error);
            throw failure;
        }
    }

    private boolean assetCallerAllowed() {
        if (Binder.getCallingUid() == Process.SYSTEM_UID) return true;
        return callerAllowed(Set.of(Constants.MODULE_PACKAGE, Constants.PKG_AICR,
                Constants.PKG_VOICE_ASSIST, "com.android.systemui"));
    }

    private static String assetName(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())
                || !Constants.PROVIDER_AUTHORITY.equals(uri.getAuthority())) return null;
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !"asset".equals(segments.get(0))) return null;
        String name = segments.get(1);
        if (!name.matches("[a-z0-9_]{1,96}\\.webp")) return null;
        return name.startsWith("base_bg_") || name.startsWith("aod_static_") ? name : null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
