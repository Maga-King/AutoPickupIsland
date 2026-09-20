package io.github.mio.autopickupisland;

import android.app.job.*;
import android.content.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One persisted network-constrained job, no polling/alarm/exact-alarm permission. */
public final class CloudSyncJob extends JobService {
    static final String PREFS = "cloud_sync_v1";
    private static final int JOB_ID = 0x504352;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private volatile Run activeRun;
    private static final class Run {
        volatile boolean stopped;
        Thread worker;
    }

    static long nextTime(long now, ZoneId zone, int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException("Time");
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(zone);
        ZonedDateTime target = local.toLocalDate().atTime(hour, minute).atZone(zone);
        if (!target.isAfter(local)) target = local.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone);
        return target.toInstant().toEpochMilli();
    }

    static boolean schedule(Context context) {
        try {
            var p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return scheduleResult(context, false, "系统调度服务不可用");
            if (!p.getBoolean("enabled", false)) {
                jobs.cancel(JOB_ID);
                return scheduleResult(context, true, "");
            }
            long now = System.currentTimeMillis();
            long next = nextTime(now, ZoneId.systemDefault(), p.getInt("hour", 3), p.getInt("minute", 0));
            int result = jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, CloudSyncJob.class))
                    .setMinimumLatency(Math.max(1000, next - now)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true).build());
            return scheduleResult(context, result == JobScheduler.RESULT_SUCCESS,
                    result == JobScheduler.RESULT_SUCCESS ? "" : "系统未接受任务，请检查后台限制后重试");
        } catch (RuntimeException error) {
            android.util.Log.w("PickupCloudSync", "Daily job scheduling failed", error);
            String reason = error instanceof SecurityException
                    ? "系统拒绝调度权限，请确认安装的是最新版本"
                    : "调度异常：" + error.getClass().getSimpleName();
            return scheduleResult(context, false, reason);
        }
    }

    private static boolean scheduleResult(Context context, boolean accepted, String error) {
        // Diagnostics must not turn a scheduling failure into an application crash.
        try { context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("schedule_error", error).apply(); }
        catch (RuntimeException ignored) { }
        return accepted;
    }

    static String runSync(Context context) {
        return runSync(context, false);
    }

    static String runSync(Context context, boolean fullXmlCheck) {
        if (!BUSY.compareAndSet(false, true)) return "同步正在进行";
        try {
            String xml = ColorOsRusRules.sync(context, fullXmlCheck);
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("xml_result", xml).putLong("attempt_at", System.currentTimeMillis()).apply();
            if (Thread.currentThread().isInterrupted()) return "同步被系统暂停";
            String list = syncListAndRecord(context).message;
            return "XML：" + xml + "\n小程序名单：" + list;
        } finally { BUSY.set(false); }
    }

    static ColorOsCloudRules.SyncResult runListSync(Context context) {
        if (!BUSY.compareAndSet(false, true)) return new ColorOsCloudRules.SyncResult(false, 0, "同步正在进行");
        try { return syncListAndRecord(context); }
        finally { BUSY.set(false); }
    }

    private static ColorOsCloudRules.SyncResult syncListAndRecord(Context context) {
        var result = ColorOsCloudRules.sync(context);
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("list_result", result.message).putLong("list_attempt_at", System.currentTimeMillis()).apply();
        return result;
    }

    @Override public boolean onStartJob(JobParameters params) {
        Run run = new Run();
        activeRun = run;
        run.worker = new Thread(() -> {
            try { runSync(getApplicationContext()); }
            catch (RuntimeException ignored) { }
            finally {
                new android.os.Handler(getMainLooper()).post(() -> {
                    if (activeRun == run && !run.stopped) {
                        activeRun = null;
                        jobFinished(params, false); schedule(getApplicationContext());
                    }
                });
            }
        }, "DailyColorOsSync");
        try { run.worker.start(); return true; }
        catch (RuntimeException error) {
            if (activeRun == run) activeRun = null;
            schedule(getApplicationContext()); return false;
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        Run run = activeRun; activeRun = null;
        if (run != null) {
            run.stopped = true;
            Thread t = run.worker; if (t != null) t.interrupt();
        }
        // No immediate retry loop. The next daily run is sufficient for rule maintenance.
        schedule(getApplicationContext()); return false;
    }

    public static final class TimeChangedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) schedule(context);
        }
    }
}
