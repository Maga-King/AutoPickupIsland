package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.util.Map;

/** Same-signature, explicit audit: schedules far-future jobs only, no cloud requests.
 * Restores the user's three scheduling preferences and reschedules their original time.
 */
public final class ScheduleInstrumentation extends Instrumentation {
    private Bundle arguments;
    private static final String PKG = "io.github.mio.autopickupisland";
    private static final int ID = 0x504352;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(PKG.equals(context.getPackageName()) && context.getApplicationInfo().uid == android.os.Process.myUid(), "Wrong target");
            check(arguments != null && "schedule-only".equals(arguments.getString("operation")), "Explicit operation required");
            check(context.getPackageManager().getPackageInfo(PKG, 0).getLongVersionCode()
                    == Long.parseLong(arguments.getString("expected_version", "-1")), "Unexpected version");
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            check(jobs != null, "No scheduler");
            if ("true".equals(arguments.getString("expect_denied"))) {
                check(context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
                        == PackageManager.PERMISSION_DENIED, "Permission unexpectedly present");
                try {
                    jobs.schedule(new JobInfo.Builder(ID, new ComponentName(PKG, PKG + ".CloudSyncJob"))
                            .setMinimumLatency(12 * 3600000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true).build());
                    throw new IllegalStateException("Expected SecurityException");
                } catch (SecurityException expected) {
                    result.putString("reproduced", expected.toString());
                }
            } else {
                check(context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
                        == PackageManager.PERMISSION_GRANTED, "Missing network-state permission");
                Method schedule = Class.forName(PKG + ".CloudSyncJob", true, context.getClassLoader())
                        .getDeclaredMethod("schedule", Context.class);
                schedule.setAccessible(true);
                SharedPreferences prefs = context.getSharedPreferences("cloud_sync_v1", Context.MODE_PRIVATE);
                Map<String, ?> saved = prefs.getAll();
                try {
                    for (int hours : new int[]{12, 13}) {
                        ZonedDateTime target = ZonedDateTime.now().plusHours(hours);
                        check(prefs.edit().putBoolean("enabled", true).putInt("hour", target.getHour())
                                .putInt("minute", target.getMinute()).commit(), "Preference save failed");
                        check(Boolean.TRUE.equals(schedule.invoke(null, context)), "Schedule rejected");
                        JobInfo job = jobs.getPendingJob(ID);
                        check(job != null && job.isPersisted() && job.getRequiredNetwork() != null, "Job missing constraints");
                        check(job.getMinLatencyMillis() > (hours * 3600000L - 65000)
                                && job.getMinLatencyMillis() <= hours * 3600000L, "Wrong time");
                        check(jobs.getAllPendingJobs().stream().filter(j -> j.getId() == ID).count() == 1, "Duplicate job");
                        check(prefs.getString("schedule_error", "missing").isEmpty(), "Stale error");
                    }
                    prefs.edit().putBoolean("enabled", false).commit();
                    check(Boolean.TRUE.equals(schedule.invoke(null, context)) && jobs.getPendingJob(ID) == null, "Disable failed");
                    prefs.edit().putInt("hour", 0).putInt("minute", 7).commit();
                    check(Boolean.TRUE.equals(schedule.invoke(null, context)) && jobs.getPendingJob(ID) == null
                            && prefs.getInt("minute", -1) == 7, "Disabled time setting failed");
                    result.putString("verified", "permission, save, schedule, replace, one-job, disable, disabled-save");
                } finally {
                    SharedPreferences.Editor edit = prefs.edit();
                    for (String key : new String[]{"enabled", "hour", "minute"}) {
                        Object value = saved.get(key);
                        if (value instanceof Boolean b) edit.putBoolean(key, b);
                        else if (value instanceof Integer n) edit.putInt(key, n);
                        else edit.remove(key);
                    }
                    check(edit.commit(), "Restore preferences failed");
                    check(Boolean.TRUE.equals(schedule.invoke(null, context)), "Restore schedule failed");
                    result.putBoolean("user_settings_restored", true);
                }
            }
            result.putBoolean("completed", true);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
