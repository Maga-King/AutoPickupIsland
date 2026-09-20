package io.github.mio.autopickupisland;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Event-only, single-flight name lookup. Never publishes a new card or retries. */
final class CloudNameEnrichment {
    interface Apply { boolean update(String name) throws Exception; }
    private static Job active;
    private static long lastStart = -CloudNamePolicy.WINDOW_MS;
    private static final class Job {
        final int id;
        final String instance, identity, code;
        final long revision, deadline;
        volatile boolean cancelled;
        Job(int id, String instance, String identity, String code, long revision, long now) {
            this.id = id; this.instance = instance; this.identity = identity; this.code = code;
            this.revision = revision; this.deadline = now + CloudNamePolicy.WINDOW_MS;
        }
    }
    static void offer(Context context, PickupEvent source, String code, String localName, int state,
            int id, String instance, Apply apply) {
        Job job = null;
        try {
            var settings = BridgeClient.cloudTextSettings(context);
            if (!settings.enabled()) return;
            PickupEvent event = PickupEvent.fromBundle(source.toBundle());
            if (event == null || !event.isFresh(Constants.EVENT_MAX_AGE_MS) || event.content.isBlank()
                    || event.content.length() > ColorOsCloudTextClient.MAX_TEXT) return;
            RuleRepository repo = RuleRepository.get(context);
            Rule rule = repo.recognitionRule(context, event);
            if (!repo.recognitionPolicy().remoteTextEligible(rule, event.recognitionPath(), !code.isEmpty(), state)) {
                status("当前原版规则不允许云文本，保留本地名称"); return;
            }
            long now = SystemClock.elapsedRealtime();
            synchronized (CloudNameEnrichment.class) {
                if (active != null || now - lastStart < CloudNamePolicy.WINDOW_MS) {
                    status("单请求/30秒限流，保留本地名称"); return;
                }
                job = new Job(id, instance, NativePickupOwnership.identity(event), code, settings.revision(), now);
                active = job; lastStart = now;
            }
            Job owned = job;
            new Thread(() -> run(context, event, localName, owned, apply), "MioCloudPickupName").start();
        } catch (Throwable failure) {
            finish(job); status("云文本准备失败，保留本地名称");
        }
    }
    private static void run(Context context, PickupEvent event, String localName, Job job, Apply apply) {
        boolean queued = false;
        try {
            if (!live(context, job)) return;
            status("正在使用原版云文本算法补全名称（不上传图片）");
            var result = ColorOsCloudTextClient.recognize(ColorOsRecognizer.originalLoader(context), event.content,
                    () -> live(context, job));
            String name = CloudNamePolicy.product(job.code, result.getStringArrayList("orderCodeList"),
                    result.getString("orderStatus", ""), result.getString("productName", ""));
            if (name.isEmpty()) { status("云结果号码/状态/名称未通过校验，保留本地名称"); return; }
            if (name.equals(localName)) { status("云文本名称与本地相同，无需更新"); return; }
            queued = new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    synchronized (CloudNameEnrichment.class) {
                        // Serialize against before-cancel guards: either update
                        // enqueues first and cancel follows, or cancelled jobs never notify.
                        if (!live(context, job)) { status("设置已变化或旧结果已失效，未回填"); return; }
                        status(apply.update(name) ? "原版云文本名称已回填当前卡片" : "原卡片已变化/消失或模板不支持，未回填");
                    }
                } catch (Throwable ignored) { status("名称回填失败，原卡片保留"); }
                finally { finish(job); }
            });
        } catch (Throwable failure) {
            // No page, header, signed URL, decrypted response or exception message.
            status("云文本失败/取消（" + failure.getClass().getSimpleName() + "），保留本地名称");
        } finally { if (!queued) finish(job); }
    }
    private static boolean live(Context context, Job job) {
        var settings = BridgeClient.cloudTextSettings(context);
        return CloudNamePolicy.current(settings.enabled(), job.cancelled, job.revision, settings.revision(),
                SystemClock.elapsedRealtime(), job.deadline, job.instance, job.instance, job.code, job.code);
    }
    static synchronized void cancel(int id) {
        if (active != null && active.id == id) active.cancelled = true;
    }
    static synchronized void cancelAll() { if (active != null) active.cancelled = true; }
    static synchronized void candidate(PickupEvent event, String code) {
        if (active != null && active.identity.equals(NativePickupOwnership.identity(event))
                && !active.code.equals(code)) active.cancelled = true;
    }
    private static synchronized void finish(Job job) { if (job != null && active == job) active = null; }
    private static void status(String text) { try { HookHealth.state("cloud_name", text); } catch (Throwable ignored) { } }
    private CloudNameEnrichment() { }
}
