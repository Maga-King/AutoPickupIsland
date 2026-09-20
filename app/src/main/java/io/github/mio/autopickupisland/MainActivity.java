package io.github.mio.autopickupisland;

import android.annotation.SuppressLint;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.graphics.Insets;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

@SuppressLint("SetTextI18n")
public final class MainActivity extends AppCompatActivity {
    private static final int PICK_RULE_XML = 401;
    private TextView status;
    private MaterialSwitch localImageOcrSwitch;
    private MaterialSwitch remoteImageSwitch;
    private MaterialSwitch cloudNameSwitch;
    private TextView signalStatus;
    private TextView ruleStatus;
    private TextView cloudStatus;
    private TextView xmlCloudStatus;
    private TextView dailyStatus;
    private TextView resultStatus;
    private TextView hookStatus;
    private boolean settingsDirty;
    private boolean bindingSettings;
    private boolean syncing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        body.setBackgroundColor(getColor(R.color.settings_background));

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        ShapeableImageView portrait = new ShapeableImageView(this);
        portrait.setImageResource(R.drawable.launcher_art);
        portrait.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        portrait.setShapeAppearanceModel(portrait.getShapeAppearanceModel().toBuilder().setAllCornerSizes(dp(24)).build());
        portrait.setContentDescription("模块图标");
        hero.addView(portrait, new LinearLayout.LayoutParams(dp(76), dp(76)));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(18), 0, 0, 0);
        heading.addView(text("取餐码上岛", 29, getColor(R.color.settings_on_surface)));
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        heading.addView(text("HYPEROS 4  /  " + version, 12, getColor(R.color.settings_primary)));
        hero.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(hero);
        TextView intro = text("让取餐码，及时出现在岛上。", 15, getColor(R.color.settings_secondary));
        intro.setPadding(0, dp(18), 0, dp(8));
        body.addView(intro);

        LinearLayout overview = card(body, "自动识别");
        signalStatus = text("正在检查页面信号…", 17, getColor(R.color.settings_on_surface));
        overview.addView(signalStatus);
        description(overview, "三合一：系统装载、ColorOS 读取器、原生上岛均已内置。\n在 LSPosed 勾选系统框架及四个推荐系统应用后重启。无需额外 APK 或文件开关。\n微信及原生五商家无需勾选作用域；系统会在目标进程加载只读组件。原生覆盖瑞幸、蜜雪、肯德基、麦当劳、星巴克，读取页面由当前官方 XML 决定。保留三指手动路径。");
        resultStatus = text("尚无识别结果", 13, getColor(R.color.settings_secondary));
        overview.addView(resultStatus);
        addButton(overview, "可选无障碍补充信号", v -> openPageSignalSettings());
        description(overview, "升级自三件套：停用旧的系统采集装载实验模块，再重启。内置读取器不依赖旧采集组件。");

        section(body, "识别策略");
        LinearLayout fallbacks = card(body, "可选识别增强");
        description(fallbacks, "本地原解析器先上岛；云文本仅尝试补全新自动卡片的商品名，不改取餐码、确认按钮或跳转。实验功能仍待实机验收。图像设置尚未接通；与 XML / 白名单下载不同。");
        cloudNameSwitch = toggle(fallbacks, "云端商品名补全 · 实验性", "默认关闭，应用时需确认上传订单页结构化文字。仅原版规则允许的页面；不上传截图。同一卡片尝试一次，最多每30秒一次、同时一个请求；失败保留本地名称。");
        localImageOcrSwitch = toggle(fallbacks, "本地图像 OCR · 尚未接通", "执行能力未完成，暂不可更改。");
        remoteImageSwitch = toggle(fallbacks, "ColorOS 远端图像识别 · 尚未接通", "执行能力未完成，暂不可更改，不上传图像。");
        localImageOcrSwitch.setEnabled(false);
        remoteImageSwitch.setEnabled(false);
        addButton(fallbacks, "应用设置", v -> saveFallbackSettings(), true);
        if (state != null && state.getBoolean("settings_dirty", false)) {
            localImageOcrSwitch.setChecked(state.getBoolean("local_draft"));
            remoteImageSwitch.setChecked(state.getBoolean("remote_draft"));
            cloudNameSwitch.setChecked(state.getBoolean("cloud_name_draft"));
            settingsDirty = true;
        }

        section(body, "规则与资源");
        LinearLayout rules = card(body, "ColorOS 策略");
        ruleStatus = text("正在读取页面规则…", 15, getColor(R.color.settings_on_surface));
        cloudStatus = text("正在读取云白名单…", 14, getColor(R.color.settings_secondary));
        rules.addView(ruleStatus);
        rules.addView(cloudStatus);
        description(rules, "页面 XML 与云白名单是两层配置。同步白名单不会替换 XML，也不包含动画模型。");
        description(rules, "内置一加 13 固件的全部 47 条扫描规则：38 条微信、3 条支付宝、6 条应用。规则存在不代表每个场景均已实机验证；详情见默认策略说明。");
        addButton(rules, "一加 13 默认策略与适配情况", v -> new MaterialAlertDialogBuilder(this)
                .setTitle("ColorOS 固件默认策略")
                .setMessage("依据一加 13 固件 XML 20260225，已完整内置，另有 4 条合成测试规则。\n\n本地原生应用识别、已适配的微信 DOM 读取、上岛和动画默认启用；支付宝及部分 Web 容器仍需实测。\n\n官方 useAccessibility=false：无需打开模块无障碍服务。use_remote_img=false、remote_img_send_card=false：远端图像默认关闭。\n\n记忆小布按要求排除。帮看与取餐订单进度有关，但该固件 Gleaner 的 pickupCode.enabled 与 enable_v_1 均为 false，当前不启动后台观察服务，不引入打车等无关场景。\n\n原 PCR 插件已带云端文本客户端及 KMS 加密；跨系统云端调用、模糊匹配与完整等待生命周期尚未完全接通，不把配置开关当作适配成功。\n\nXML 云下发与服务名单分开查询；未获得并应用 XML 时继续使用当前规则，不标成最新。快递是独立链路，此取餐 XML 没有快递规则。")
                .setPositiveButton("知道了", null).show());
        addButton(rules, "手动同步云小程序名单", v -> syncCloudRules());
        xmlCloudStatus = text("", 14, getColor(R.color.settings_secondary));
        rules.addView(xmlCloudStatus);
        description(rules, "RUS 完整 XML 经官方 HTTPS、压缩包/文件校验及防回退检查后应用。打包等待或未知增量保留旧规则；与名单分别报告结果，不应用其他系统配置。");
        addButton(rules, "同步名单与 XML 规则", v -> syncAllRules());
        addButton(rules, "重新校验完整 XML", v -> syncAllRules(true));
        description(rules, "日常同步会带缓存校验值，服务可能省略未变化的 XML。完整校验重新请求正文并核对校验值；相同规则不重复应用。失败不清空缓存。");
        var syncPrefs = getSharedPreferences(CloudSyncJob.PREFS, MODE_PRIVATE);
        MaterialSwitch daily = toggle(rules, "每日自动同步", "同步名单与 XML；到设定时间后联网执行，系统省电可能延后。默认关闭。");
        daily.setChecked(syncPrefs.getBoolean("enabled", false));
        daily.setOnCheckedChangeListener((button, enabled) -> {
            syncPrefs.edit().putBoolean("enabled", enabled).apply();
            if (!CloudSyncJob.schedule(this)) Toast.makeText(this, "定时任务设置失败", Toast.LENGTH_LONG).show();
            refreshCloudSyncStatus();
        });
        dailyStatus = text("", 14, getColor(R.color.settings_secondary));
        rules.addView(dailyStatus);
        addButton(rules, "设置每日同步时间", v -> new android.app.TimePickerDialog(this, (picker, hour, minute) -> {
            syncPrefs.edit().putInt("hour", hour).putInt("minute", minute).apply();
            if (!CloudSyncJob.schedule(this)) Toast.makeText(this, "定时任务设置失败", Toast.LENGTH_LONG).show();
            refreshCloudSyncStatus();
        }, syncPrefs.getInt("hour", 3), syncPrefs.getInt("minute", 0), true).show());
        refreshCloudSyncStatus();
        addButton(rules, "导入 ColorOS / RUS 规则 XML", v -> chooseRules());

        section(body, "测试与维护");
        LinearLayout tools = card(body, "工具");
        hookStatus = text("", 13, getColor(R.color.settings_secondary));
        tools.addView(hookStatus);
        addButton(tools, "刷新 Hook 状态", v -> refreshStatus());
        addButton(tools, "打开全部场景测试", v -> launchFixture());
        addButton(tools, "一键清除已取餐记录", v -> new MaterialAlertDialogBuilder(this)
                .setTitle("清除已取餐记录？")
                .setMessage("解除本模块所有已确认取餐码的当日屏蔽。不会确认订单、关闭当前卡片或删除云规则；再次进入订单页可能重新上岛。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> safeRun(this::clearClosedHistory)).show());
        description(tools, "已取餐记录最多保留 512 条，记录新确认时清理跨日记录；无需后台定时清理。");
        addButton(tools, "仅清除诊断与临时去重", v -> new MaterialAlertDialogBuilder(this)
                .setTitle("清除识别状态？").setMessage("清除识别记录与模块去重缓存，保留云规则和识别设置。不会代替你确认取餐。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> safeRun(this::clearState)).show());
        addButton(tools, "重置为内置默认策略", v -> new MaterialAlertDialogBuilder(this)
                .setTitle("恢复默认策略？").setMessage("移除已同步 / 导入的策略，关闭云文本补全与图像兜底开关。系统中的无障碍服务开关保持不变。")
                .setNegativeButton("取消", null).setPositiveButton("重置", (d, w) -> safeRun(this::resetDefaults)).show());
        addButton(tools, "超级小爱通知设置", v -> openNotificationSettings());
        status = text("正在读取状态…", 13, getColor(R.color.settings_secondary));
        status.setTextIsSelectable(true);
        status.setVisibility(View.GONE);
        addButton(tools, "展开 / 收起诊断详情", v -> status.setVisibility(status.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        tools.addView(status);
        description(body, "事件驱动，无常驻 OCR。进入支持页面后仍有短时补采；实际开销随页面事件和兜底设置变化。\nMaterial 3 · 动画资源来自本地 ColorOS 解包");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("settings_dirty", settingsDirty);
        out.putBoolean("cloud_name_draft", cloudNameSwitch.isChecked());
        out.putBoolean("local_draft", localImageOcrSwitch.isChecked());
        out.putBoolean("remote_draft", remoteImageSwitch.isChecked());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_RULE_XML || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("无法打开文件");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 500_000) throw new IllegalArgumentException("规则超过 500 KB");
            }
            Bundle extras = new Bundle();
            extras.putString("xml", bytes.toString(StandardCharsets.UTF_8));
            Bundle result = getContentResolver().call(
                    Constants.PROVIDER_URI, "install_rules", null, extras);
            boolean accepted = result != null && result.getBoolean("accepted", false);
            Toast.makeText(this, accepted ? "页面规则已应用，下次页面事件使用新配置" : "XML 不是有效的扫描规则",
                    Toast.LENGTH_LONG).show();
            refreshStatus();
        } catch (Exception error) {
            Toast.makeText(this, "导入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        try {
            Bundle state = getContentResolver().call(Constants.PROVIDER_URI, "status", null, null);
            if (state == null) throw new IllegalStateException("Provider 无响应");
            refreshHookHealth(state);
            if (!settingsDirty) {
                bindingSettings = true;
                localImageOcrSwitch.setChecked(state.getBoolean("local_image_ocr", false));
                remoteImageSwitch.setChecked(state.getBoolean("remote_image_recognition", false));
                cloudNameSwitch.setChecked(state.getBoolean("cloud_text_name", false));
                bindingSettings = false;
            }
            RuleRepository repository = RuleRepository.get(this);
            String code = state.getString("code", "");
            long eventTime = state.getLong("event_time", 0L);
            String ruleSource = "rus".equals(state.getString("rules_source", "")) ? "ColorOS 云端 XML"
                    : state.getBoolean("overlay", false) ? "外部导入" : "内置规则";
            signalStatus.setText(pageSignalEnabled() ? "内置自动识别 + 无障碍补充信号" : "内置自动识别（无需无障碍开关）");
            ruleStatus.setText("页面扫描 XML  ·  " + repository.size() + " 条 / " + repository.version()
                    + "\n来源：" + ruleSource);
            cloudStatus.setText("云小程序白名单  ·  " + state.getInt("cloud_rule_count", 0) + " 个\n"
                    + "最近成功：" + formatTime(state.getLong("cloud_updated_at", 0L))
                    + "\n最近查询：" + getSharedPreferences(CloudSyncJob.PREFS, MODE_PRIVATE).getString("list_result", "尚未查询")
                    + "\n" + formatTime(getSharedPreferences(CloudSyncJob.PREFS, MODE_PRIVATE).getLong("list_attempt_at", 0)));
            resultStatus.setText("最近：" + state.getString("state", "尚无结果") + (code.isEmpty() ? "" : " · " + code));
            status.setText("页面扫描 XML：" + repository.size() + " 条 / ColorOS " + repository.version()
                    + " / " + ruleSource
                    + "\n页面变化触发：" + (pageSignalEnabled() ? "已在系统中启用" : "未启用（保留原有自动识别）")
                    + "\n最近状态：" + state.getString("state", "尚无结果")
                    + (code.isEmpty() ? "" : " · " + code)
                    + "\n识别引擎：" + state.getString("detail", "")
                    + "\n来源：" + state.getString("source", "")
                    + "\n云小程序白名单：" + (state.getInt("cloud_rule_count", 0) > 0 ? "已保存 " : "未同步 ")
                    + state.getInt("cloud_rule_count", 0) + " 个"
                    + " / " + formatTime(state.getLong("cloud_updated_at", 0L))
                    + "\n事件时间：" + (eventTime == 0L ? "—"
                    : DateFormat.getDateTimeInstance().format(new Date(eventTime))));
        } catch (Exception error) {
            status.setText("状态读取失败：" + error);
        }
    }

    private void saveFallbackSettings() {
        if (cloudNameSwitch.isChecked() && !BridgeClient.cloudTextSettings(this).enabled()) {
            new MaterialAlertDialogBuilder(this).setTitle("允许云端补全商品名？")
                    .setMessage("会将已匹配订单页的结构化文字（可能含门店、订单等信息）发送至 ColorOS 云端服务，不上传截图。仅对新自动卡片尝试补名称。关闭可阻止后续请求及回填，已发送的内容无法撤回。")
                    .setPositiveButton("同意并应用", (dialog, which) -> persistFallbackSettings(true))
                    .setNegativeButton("取消", null).show();
            return;
        }
        persistFallbackSettings(false);
    }

    private void persistFallbackSettings(boolean consent) {
        Bundle extras = new Bundle();
        extras.putBoolean("cloud_text_name", cloudNameSwitch.isChecked());
        extras.putBoolean("cloud_text_consent", consent);
        extras.putBoolean("local_image_ocr", localImageOcrSwitch.isChecked());
        extras.putBoolean("remote_image_recognition", remoteImageSwitch.isChecked());
        Bundle result = getContentResolver().call(
                Constants.PROVIDER_URI, "set_fallbacks", null, extras);
        boolean accepted = result != null && result.getBoolean("accepted", false);
        if (accepted) settingsDirty = false;
        Toast.makeText(this, accepted ? "设置已应用" : "设置保存失败", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void launchFixture() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(Constants.PKG_TEST_FIXTURE);
        if (intent == null) {
            Toast.makeText(this, "请先安装配套测试 APK", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    private void chooseRules() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("text/xml")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_RULE_XML);
    }

    private void syncCloudRules() {
        if (syncing) return;
        syncing = true;
        cloudStatus.setText("正在从 ColorOS 官方规则服务同步…");
        new Thread(() -> {
            ColorOsCloudRules.SyncResult result = CloudSyncJob.runListSync(getApplicationContext());
            runOnUiThread(() -> {
                syncing = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        }, "ColorOsRuleSync").start();
    }

    private void refreshCloudSyncStatus() {
        var prefs = getSharedPreferences(CloudSyncJob.PREFS, MODE_PRIVATE);
        xmlCloudStatus.setText("识别 XML：" + prefs.getString("xml_result", "尚未查询")
                + "\n最近尝试：" + formatTime(prefs.getLong("attempt_at", 0)));
        String scheduleError = prefs.getString("schedule_error", "");
        dailyStatus.setText(String.format(java.util.Locale.ROOT, "每日 %02d:%02d（系统调度，非精确闹钟）",
                prefs.getInt("hour", 3), prefs.getInt("minute", 0))
                + (prefs.getBoolean("enabled", false) ? "" : "\n自动同步已关闭；时间仍会保存")
                + (scheduleError.isEmpty() ? "" : "\n尚未成功调度：" + scheduleError));
    }

    private void syncAllRules() {
        syncAllRules(false);
    }

    private void syncAllRules(boolean fullXmlCheck) {
        if (syncing) return;
        syncing = true;
        xmlCloudStatus.setText(fullXmlCheck ? "正在重新获取并校验完整 XML，同时核对小程序名单…" : "正在查询 RUS XML，并同步小程序名单…");
        new Thread(() -> {
            String result;
            try { result = CloudSyncJob.runSync(getApplicationContext(), fullXmlCheck); }
            catch (RuntimeException error) { result = "同步失败；保留当前规则"; }
            final String message = result;
            runOnUiThread(() -> {
                syncing = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                refreshCloudSyncStatus(); refreshStatus();
            });
        }, "ColorOsFullRuleSync").start();
    }

    private void resetDefaults() {
        Bundle result = getContentResolver().call(
                Constants.PROVIDER_URI, "reset_defaults", null, null);
        boolean accepted = result != null && result.getBoolean("accepted", false);
        if (accepted) settingsDirty = false;
        Toast.makeText(this, accepted ? "已恢复内置默认策略" : "重置失败",
                Toast.LENGTH_SHORT).show();
        RuleRepository.invalidate();
        refreshStatus();
    }

    private void clearState() {
        getContentResolver().call(Constants.PROVIDER_URI, "clear", null, null);
        Toast.makeText(this, "状态已清除", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshHookHealth(Bundle state) {
        StringBuilder text = new StringBuilder("Hook 最近上报（非持续存活检测；回调最多每 30 秒上报）\n");
        String[] roles = {"loader", "voice", "aicr", "assist"};
        String[] names = {"系统装载 / 内置读取器", "超级小爱", "澎湃 AI 引擎", "AI 通话"};
        String[] packages = {"android", Constants.PKG_VOICE_ASSIST, Constants.PKG_AICR, Constants.PKG_PRIVILEGED_ASSIST};
        for (int i = 0; i < roles.length; i++) {
            text.append("\n").append(names[i]).append("：\n");
            try {
                String raw = state.getString("hook_health_" + roles[i], "");
                if (raw.isEmpty()) { text.append("未收到上报，不能判断已加载\n"); continue; }
                var json = new org.json.JSONObject(raw);
                int boot = Settings.Global.getInt(getContentResolver(), "boot_count", -1);
                boolean stale = boot != json.optInt("boot", -2)
                        || getPackageManager().getPackageInfo(packages[i], 0).getLongVersionCode() != json.optLong("version")
                        || !getApplicationInfo().sourceDir.equals(json.optString("module_path"));
                text.append(stale ? "旧版本/旧启动记录，需重载目标进程\n" : "当前版本上报（不保证进程仍存活）\n");
                text.append("PID ").append(json.optInt("pid")).append(" · ").append(formatTime(json.optLong("at"))).append("\n");
                for (String key : new String[]{"entry", "loader", "loader_error", "factory", "visibility", "manager", "notification", "ingress", "reader", "reader_result", "observer_direct", "observer_fallback", "dedup", "callback", "install_error"})
                    if (json.has(key)) text.append(json.optString(key)).append("\n");
            } catch (Exception error) { text.append("状态读取失败\n"); }
        }
        hookStatus.setText(text);
    }

    private boolean clearingClosed;
    private void clearClosedHistory() {
        if (clearingClosed) return;
        clearingClosed = true;
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout = () -> {
            if (!finished.compareAndSet(false, true)) return;
            clearingClosed = false;
            if (!isDestroyed()) Toast.makeText(this, "未收到清除回执，请检查小爱模块是否已加载", Toast.LENGTH_LONG).show();
        };
        handler.postDelayed(timeout, 8_000L);
        try {
            Intent request = new Intent(Constants.ACTION_CLEAR_CLOSED)
                    .setComponent(new android.content.ComponentName(Constants.PKG_VOICE_ASSIST,
                            Constants.VOICE_ASSIST_WAKE_RECEIVER))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true).setDeferralPolicy(android.app.BroadcastOptions.DEFERRAL_POLICY_NONE);
            sendOrderedBroadcast(request, null, options.toBundle(), new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, Intent intent) {
                    if (!finished.compareAndSet(false, true)) return;
                    handler.removeCallbacks(timeout);
                    clearingClosed = false;
                    if (isDestroyed()) return;
                    if (getResultCode() == RESULT_OK) {
                        // Clear AICR pending/dedupe and the reader's cached page state too.
                        try {
                            getContentResolver().call(Constants.PROVIDER_URI, "clear", null, null);
                        } catch (RuntimeException error) {
                            Toast.makeText(MainActivity.this, "已清除已取餐记录，但临时缓存重置失败，请重试", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    Toast.makeText(MainActivity.this, getResultCode() == RESULT_OK
                            ? "已清除 " + getResultData() + " 条已取餐记录，已请求重置临时缓存；重新进入订单页重测"
                            : "清除未成功，请检查小爱模块是否已加载", Toast.LENGTH_LONG).show();
                }
            }, handler, RESULT_CANCELED, null, null);
        } catch (RuntimeException error) {
            handler.removeCallbacks(timeout);
            finished.set(true);
            clearingClosed = false;
            Toast.makeText(this, "清除请求失败", Toast.LENGTH_LONG).show();
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, Constants.PKG_VOICE_ASSIST);
        startActivity(intent);
    }

    private boolean pageSignalEnabled() {
        android.view.accessibility.AccessibilityManager manager =
                getSystemService(android.view.accessibility.AccessibilityManager.class);
        if (manager == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            android.content.pm.ServiceInfo service = info.getResolveInfo().serviceInfo;
            if (getPackageName().equals(service.packageName)
                    && PageChangeSignalService.class.getName().equals(service.name)) return true;
        }
        return false;
    }

    private void openPageSignalSettings() {
        new MaterialAlertDialogBuilder(this).setTitle("可选的页面变化信号")
                .setMessage("由本模块独立接收内容变化事件，不借用系统截屏服务；不读取无障碍节点、事件文本，不执行点击。仅对规则中的前台场景触发结构化识别，最多每秒一个信号，无常驻 OCR。此路径不是 ColorOS OEM 框架的完整移植。需你在系统中手动开启，关闭不影响原有自动识别和三指路径。")
                .setNegativeButton("取消", null).setPositiveButton("去系统设置", (dialog, which) -> {
                    try {
                        startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                                .putExtra("android.intent.extra.COMPONENT_NAME",
                                        new android.content.ComponentName(this, PageChangeSignalService.class)));
                    } catch (RuntimeException error) {
                        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                        catch (RuntimeException ignored) { Toast.makeText(this, "请手动打开系统无障碍设置", Toast.LENGTH_LONG).show(); }
                    }
                }).show();
    }

    private void addButton(LinearLayout parent, String label, android.view.View.OnClickListener listener) {
        addButton(parent, label, listener, false);
    }

    private void addButton(LinearLayout parent, String label, android.view.View.OnClickListener listener, boolean primary) {
        MaterialButton button = new MaterialButton(this, null, primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setCornerRadius(dp(20));
        button.setMinimumHeight(dp(52));
        button.setOnClickListener(v -> safeRun(() -> listener.onClick(v)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(5);
        parent.addView(button, params);
    }

    private void safeRun(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { Toast.makeText(this, "操作未完成：" + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
    }

    private LinearLayout card(LinearLayout parent, String title) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(26));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setCardBackgroundColor(getColor(R.color.settings_surface));
        LinearLayout inside = new LinearLayout(this);
        inside.setOrientation(LinearLayout.VERTICAL);
        inside.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView heading = text(title, 13, getColor(R.color.settings_primary));
        heading.setPadding(0, 0, 0, dp(10));
        inside.addView(heading);
        card.addView(inside);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(card, params);
        return inside;
    }

    private void section(LinearLayout parent, String label) {
        TextView title = text(label, 20, getColor(R.color.settings_on_surface));
        title.setPadding(dp(4), dp(28), 0, 0);
        parent.addView(title);
    }

    private void description(LinearLayout parent, String label) {
        TextView text = text(label, 13, getColor(R.color.settings_secondary));
        text.setPadding(0, dp(8), 0, dp(12));
        parent.addView(text);
    }

    private MaterialSwitch toggle(LinearLayout parent, String label, String hint) {
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setText(label);
        toggle.setTextSize(16);
        toggle.setTextColor(getColor(R.color.settings_on_surface));
        toggle.setMinimumHeight(dp(52));
        toggle.setOnCheckedChangeListener((button, checked) -> { if (!bindingSettings) settingsDirty = true; });
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        description(parent, hint);
        return toggle;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatTime(long value) {
        return value <= 0L ? "尚未同步"
                : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
