package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Method;

/** Explicit, same-signature test APK instrumentation. No new exported main-module API.
 * Runs the real module's synchronization under its own process/UID, without touching
 * the foreground ordering app. No page capture or order confirmation.
 */
public final class CloudSyncInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (arguments == null || !"sync-rules".equals(arguments.getString("operation")))
                throw new IllegalArgumentException("Explicit sync-rules operation required");
            Context target = getTargetContext();
            String pkg = "io.github.mio.autopickupisland";
            if (!pkg.equals(target.getPackageName()) || target.getApplicationInfo().uid != android.os.Process.myUid())
                throw new SecurityException("WrongTargetProcess");
            long expected = Long.parseLong(arguments.getString("expected_version", "-1"));
            long installed = target.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
            if (expected != installed || expected <= 0) throw new IllegalStateException("UnexpectedInstalledVersion");
            ClassLoader loader = target.getClassLoader();
            Method sync = Class.forName(pkg + ".CloudSyncJob", true, loader).getDeclaredMethod("runSync", Context.class);
            sync.setAccessible(true);
            result.putString("sync_result", String.valueOf(sync.invoke(null, target)));
            Uri provider = Uri.parse("content://io.github.mio.autopickupisland.bridge");
            Bundle status = target.getContentResolver().call(provider, "status", null, null);
            Bundle config = target.getContentResolver().call(provider, "config", null, null);
            if (status == null || config == null) throw new IllegalStateException("NoProviderReceipt");
            result.putString("rules_source", status.getString("rules_source", ""));
            result.putString("rules_md5", config.getString("rules_rus_md5", ""));
            result.putLong("rules_revision", config.getLong("rules_revision", -1));
            if ("rus".equals(status.getString("rules_source", ""))) {
                Bundle rules = target.getContentResolver().call(provider, "rules", null, null);
                if (rules == null) throw new IllegalStateException("NoActiveXml");
                Bundle same = new Bundle();
                same.putString("xml", rules.getString("xml", ""));
                same.putString("md5", config.getString("rules_rus_md5", ""));
                same.putString("metadata_version", target.getSharedPreferences("bridge_state", Context.MODE_PRIVATE)
                        .getString("rules_rus_metadata_version", ""));
                same.putLong("expected_revision", config.getLong("rules_revision", -1));
                Bundle repeated = target.getContentResolver().call(provider, "install_rus_rules", null, same);
                Bundle after = target.getContentResolver().call(provider, "config", null, null);
                boolean idempotent = repeated != null && repeated.getBoolean("accepted", false)
                        && repeated.getBoolean("unchanged", false) && after != null
                        && after.getLong("rules_revision", -2) == config.getLong("rules_revision", -1);
                result.putBoolean("same_xml_no_reload", idempotent);
                if (!idempotent) throw new IllegalStateException("SameXmlReloaded");
            }
            Class<?> repository = Class.forName(pkg + ".RuleRepository", true, loader);
            Method get = repository.getDeclaredMethod("get", Context.class);
            Method version = repository.getDeclaredMethod("version");
            Method size = repository.getDeclaredMethod("size");
            get.setAccessible(true); version.setAccessible(true); size.setAccessible(true);
            Object active = get.invoke(null, target);
            result.putString("active_xml_version", String.valueOf(version.invoke(active)));
            result.putInt("active_rule_count", (Integer) size.invoke(active));
            result.putBoolean("completed", true);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null
                    ? error.getCause() : error;
            result.putString("failure", cause.getClass().getSimpleName() + ":" + cause.getMessage());
            result.putBoolean("completed", false);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
