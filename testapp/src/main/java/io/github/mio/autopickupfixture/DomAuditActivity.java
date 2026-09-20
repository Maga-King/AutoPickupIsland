package io.github.mio.autopickupfixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.mio.autopickupisland.coloros.ColorOsWebProtocol;
import io.github.mio.autopickupisland.coloros.ColorOsWebTextParser;
import io.github.mio.autopickupisland.coloros.ColorOsWebViewClient;
import io.github.mio.autopickupisland.coloros.ColorOsWebViewClient.Request;
import io.github.mio.autopickupisland.coloros.ColorOsWebViewClient.Status;
import io.github.mio.autopickupisland.coloros.ColorOsWebViewSelector;
import io.github.mio.autopickupisland.coloros.ColorOsActivityCapture;
import io.github.mio.autopickupisland.coloros.OriginalColorOsWebReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real renderer tests on synthetic, in-memory pages. No bridge publishing or
 * network permission. FLAG_SECURE keeps the running automatic module out of this audit. */
@SuppressLint("SetJavaScriptEnabled")
public final class DomAuditActivity extends Activity {
    private static final String DETAIL = "pages/order/detail/detail";
    private static final String LIST = "pages/order/list";
    private static final String WX_ID = "wx0123456789abcdef";
    private static final String QUERY = "orderId=fixture-001&raw=%2F";
    private static final String TAG = "MioDomAudit";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pcrWorker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private final StringBuilder report = new StringBuilder();
    private ColorOsWebViewClient client;
    private OriginalColorOsWebReader originalReader;
    private boolean originalMode;
    private WebView left, right;
    private TextView status;
    private Button start;
    private OriginalPcrFixture pcr;
    private ColorOsWebTextParser.State latest;
    private boolean running, launched;
    private int completed, epoch, loadSerial;
    private String runId = "", phase = "";
    private Runnable done;
    private final Runnable deadline = () -> fail("OverallTimeout");
    private record Step(String label, Action action) { }
    private interface Action { void run() throws Exception; }
    private interface Inspect { void accept(String raw) throws Exception; }
    private static final ColorOsWebTextParser.PathPolicy POLICY = new ColorOsWebTextParser.PathPolicy() {
        @Override public List<String> filterPaths() { return List.of(DETAIL, LIST); }
        @Override public boolean useCloud(String route) { return DETAIL.equals(route); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        originalMode = getIntent().getBooleanExtra("original_reader", false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        client = new ColorOsWebViewClient();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(20, 70, 20, 30);
        status = new TextView(this);
        status.setTextSize(15);
        // The full report stays in preferences. Keep status height bounded so
        // late native isVisibleToUser tests do not push their WebViews offscreen.
        status.setMaxLines(5);
        status.setTextColor(Color.DKGRAY);
        status.setText("ColorOS 原协议 · 真 WebView 回归\n仅使用内存测试页面；不发卡、不跳微信、不联网。\n这是采集/解析测试，不是自动上岛验收。");
        panel.addView(status);
        start = new Button(this);
        start.setText("运行原协议与异常保护回归");
        start.setOnClickListener(v -> runAudit());
        panel.addView(start);
        left = webView(); right = webView();
        panel.addView(left, new LinearLayout.LayoutParams(-1, 430));
        panel.addView(right, new LinearLayout.LayoutParams(-1, 230));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        setContentView(scroll);
    }
    private WebView webView() {
        WebView view = new WebView(this);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setBlockNetworkLoads(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        return view;
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        if (!launched && getIntent().getBooleanExtra("run_audit", false)) {
            launched = true;
            main.post(this::runAudit);
        }
    }
    @Override protected void onStop() {
        if (running) fail("ActivityLeft");
        super.onStop();
    }
    @Override protected void onDestroy() {
        running = false;
        epoch++;
        main.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        if (left != null) left.destroy();
        if (right != null) right.destroy();
        pcrWorker.shutdownNow();
        super.onDestroy();
    }

    private void runAudit() {
        if (running) return;
        running = true;
        runId = UUID.randomUUID().toString();
        completed = 0; report.setLength(0); steps.clear(); epoch++;
        start.setEnabled(false);
        setupSteps();
        writeStatus("RUNNING");
        main.postDelayed(deadline, 45_000L);
        next();
    }
    private void add(String label, Action action) { steps.add(new Step(label, action)); }
    private void next() {
        if (!running) return;
        Step step = steps.poll();
        if (step == null) {
            running = false; main.removeCallbacks(deadline); start.setEnabled(true);
            phase = "完成";
            writeStatus("PASS");
            Log.i(TAG, "PASS cases=" + completed + " run=" + runId + " originalReader=" + originalMode
                    + " real fixture DOM + original local PCR; not full-chain");
            return;
        }
        phase = step.label;
        int currentEpoch = epoch;
        AtomicBoolean called = new AtomicBoolean();
        done = () -> {
            if (!running || epoch != currentEpoch) return;
            if (!called.compareAndSet(false, true)) { fail("DuplicateStepCompletion"); return; }
            completed++;
            report.append("✓ ").append(step.label).append('\n');
            writeStatus("RUNNING");
            main.post(this::next);
        };
        safely(step.action);
    }
    private void safely(Action action) {
        if (!running) return;
        try { action.run(); }
        catch (Throwable error) { fail(error.getClass().getSimpleName()); }
    }
    private void fail(String reason) {
        if (!running) return;
        running = false; epoch++;
        main.removeCallbacks(deadline);
        report.append("失败: ").append(phase).append(" / ").append(reason).append('\n');
        start.setEnabled(true);
        writeStatus("FAIL");
        Log.e(TAG, "FAIL phase=" + phase + " reason=" + reason + " run=" + runId);
        client.close(); client = new ColorOsWebViewClient();
    }
    private void writeStatus(String result) {
        status.setText(result + " · " + completed + "\n" + phase + "\n" + report);
        getSharedPreferences("dom_audit", MODE_PRIVATE).edit().putString("result", result)
                .putBoolean("original_reader", originalMode)
                .putString("run_id", runId).putInt("passed", completed)
                .putString("phase", phase).putString("report", report.toString()).apply();
    }
    private static void expect(boolean value) { if (!value) throw new AssertionError("FixtureExpectation"); }

    private static String page(String route, String body, boolean ali) {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{font-family:sans-serif;margin:8px}wx-root-portal-content{display:block}.hidden{display:none}</style>"
                + "<script>window.__appId__=" + JSONObject.quote(WX_ID) + ";window.__route__=" + JSONObject.quote(route)
                + ";window.__queryString__=" + JSONObject.quote(QUERY) + ";"
                + (ali ? "window.$HybridDataMineManager={envInfo:{appId:'202100fixture'}};" : "")
                + "</script></head><body>" + body + "</body></html>";
    }
    private void load(WebView view, String html, String suffix) {
        int serial = ++loadSerial;
        String address = "https://fixture.invalid/dom-audit/" + serial + "/index.html" + suffix;
        Runnable completion = done;
        int generation = epoch;
        AtomicBoolean once = new AtomicBoolean();
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (epoch != generation || !running || url == null
                        || !url.contains("/dom-audit/" + serial + "/") || !once.compareAndSet(false, true)) return;
                // Render a real layout frame before the first protocol request; not a recognition poll.
                v.postVisualStateCallback(serial, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long id) {
                        if (epoch == generation && running) completion.run();
                    }
                });
            }
        });
        view.loadDataWithBaseURL(address, html, "text/html", "UTF-8", null);
    }
    private void mutate(String javascript) {
        Runnable completion = done;
        int generation = epoch;
        left.evaluateJavascript(javascript, ignored -> {
            if (running && epoch == generation) completion.run();
        });
    }
    private void capture(View view, Request request, Inspect inspect) {
        Runnable completion = done;
        int generation = epoch;
        client.request(view, "", request, () -> running && epoch == generation && !isDestroyed(), result -> safely(() -> {
            expect(result.status() == Status.OK);
            inspect.accept(result.raw());
            completion.run();
        }));
    }
    private static Request content(boolean label, boolean portal) {
        return Request.content(List.of(DETAIL, LIST), false, label, portal);
    }
    private void remember(String raw) {
        var result = ColorOsWebTextParser.parse(raw, "", POLICY);
        expect(!result.malformed() && !result.limitExceeded() && !result.state().texts().isEmpty());
        latest = result.state();
    }
    private void pcr(String expectedCode, String expectedStatus) {
        Runnable completion = done;
        int generation = epoch;
        ColorOsWebTextParser.State input = latest;
        pcrWorker.execute(() -> {
            try {
                if (pcr == null) pcr = new OriginalPcrFixture(getApplicationContext());
                Bundle result = pcr.extract(input.texts(), "luckin", input.route);
                main.post(() -> safely(() -> {
                    if (epoch != generation) return;
                    List<String> codes = result.getStringArrayList("orderCodeList");
                    expect(codes != null);
                    expect(expectedCode.isEmpty() ? codes.isEmpty() : codes.contains(expectedCode));
                    expect(expectedStatus.equals(result.getString("orderStatus")));
                    expect("生椰拿铁".equals(result.getString("productName")));
                    completion.run();
                }));
            } catch (Throwable error) {
                main.post(() -> { if (epoch == generation) fail("OriginalPcr:" + error.getClass().getSimpleName()); });
            }
        });
    }

    private void setupSteps() {
        if (originalMode) add("装载 SHA 固定的 ColorOS 原读取器 DEX（不是 Java 转写）", () -> {
            Runnable completion = done;
            int generation = epoch;
            pcrWorker.execute(() -> {
                try {
                    OriginalColorOsWebReader loaded = OriginalColorOsWebReader.loadForFixture(getApplicationContext());
                    main.post(() -> safely(() -> {
                        if (generation != epoch) return;
                        originalReader = loaded;
                        client.close(); client = new ColorOsWebViewClient(5_000, loaded);
                        completion.run();
                    }));
                } catch (Throwable error) {
                    main.post(() -> { if (generation == epoch) fail("OriginalReader:" + error.getClass().getSimpleName()); });
                }
            });
        });
        String waiting = "<div>瑞幸咖啡</div><div id='state'>制作中</div><div id='code'></div><div>取餐码</div>"
                + "<div>生椰拿铁</div><div>冰</div><div>下单时间</div><div>2026-09-05 12:40:00</div>"
                + "<div class='hidden'>OLD_PRODUCT_9999</div>";
        add("加载独立瑞幸等待页", () -> load(left, page(DETAIL, waiting, false), ""));
        add("真实 route / wx AppID，与 gh 原始 ID 分开", () -> capture(left, Request.pageId(), raw -> {
            JSONArray a = new JSONArray(raw);
            expect(a.getString(1).equals(DETAIL) && a.getString(2).equals(WX_ID));
            expect(!a.getString(2).startsWith("gh_"));
        }));
        add("真实 DOM → 有序解析，保留商品、排除 display:none", () -> capture(left, content(false, false), raw -> {
            remember(raw);
            expect(latest.route.equals(DETAIL) && latest.query.equals(QUERY));
            expect(latest.texts().get(0).contains("生椰拿铁") && !latest.texts().get(0).contains("OLD_PRODUCT_9999"));
        }));
        add("原 PCR：没展开取餐码时 waiting，不编造数字", () -> pcr("", "waiting"));
        add("同页展开 666，不重新进入 Activity", () -> mutate("document.getElementById('code').textContent='666';document.getElementById('state').textContent='等待取餐';"));
        add("重新读取同一 WebView 的真实 666", () -> capture(left, content(false, false), this::remember));
        add("原 PCR：666 与商品名", () -> pcr("666", "uncompleted"));
        add("同页换成四位码 0521", () -> mutate("document.getElementById('code').textContent='0521';"));
        add("读取新码，不沿用旧采集结果", () -> capture(left, content(false, false), this::remember));
        add("原 PCR：0521 保留前导零与商品名", () -> pcr("0521", "uncompleted"));
        add("同页切换到非白名单路径", () -> mutate("window.__route__='pages/menu/index';"));
        add("原 JS 白名单关闭正文采集", () -> capture(left, content(false, false), raw -> {
            JSONArray a = new JSONArray(raw); expect(a.getString(1).isEmpty() && a.getString(2).equals("pages/menu/index"));
            expect(ColorOsWebTextParser.parse(raw, "", POLICY).state().texts().isEmpty());
        }));
        add("labelPathChange 只改变采集，不能当最终发卡授权", () -> capture(left, content(true, false), raw -> {
            JSONArray a = new JSONArray(raw); expect(!a.getString(1).isEmpty() && !POLICY.filterPaths().contains(a.getString(2)));
        }));
        add("删除真实路径变量", () -> mutate("delete window.__route__;"));
        add("缺路径时不填白名单或默认路由", () -> capture(left, content(false, false), raw -> {
            JSONArray a = new JSONArray(raw); expect(a.isNull(2) && a.getString(1).isEmpty());
        }));
        add("切换未知 wx AppID", () -> mutate("window.__route__=" + JSONObject.quote(DETAIL) + ";window.__appId__='wx_unknown_fixture';"));
        add("原协议回传实际 ID，不伪造规则 gh ID", () -> capture(left, content(false, false), raw -> {
            expect(new JSONArray(raw).getString(3).equals("wx_unknown_fixture"));
        }));

        String portal = "<div>商品（完整括号）</div><wx-root-portal-content><div>666</div><div>取餐码</div></wx-root-portal-content>"
                + "<wx-root-portal-content> </wx-root-portal-content><div class='hidden'>HIDDEN_NODE</div>"
                + "<div>外<span>内</span>尾</div><div style='position:absolute;top:10000px'>OFFSCREEN_NODE</div>";
        add("加载 root portal 与嵌套/隐藏/屏外节点", () -> load(left, page(DETAIL, portal, false), ""));
        add("portal 前置和重复文本完全保留", () -> capture(left, content(false, true), raw -> {
            String text = new JSONArray(raw).getString(1);
            expect(text.startsWith("666\n取餐码\n") && text.indexOf("666", 3) > 3 && text.contains("商品（完整括号）"));
        }));
        add("type5 叶节点：隐藏排除、屏外保留", () -> capture(left, new Request(5, List.of(), false, false, false, true), raw -> {
            JSONObject object = new JSONObject(raw); JSONArray nodes = object.getJSONArray("nodes");
            expect(hasText(nodes, "商品（完整括号）") && hasText(nodes, "OFFSCREEN_NODE") && !hasText(nodes, "HIDDEN_NODE"));
            expect(hasText(nodes, "内") && !hasText(nodes, "外内尾"));
            expect(object.getString("wechat_query").equals(QUERY));
        }));
        add("type5 全节点：直接子文本、原隐藏节点语义", () -> capture(left, new Request(5, List.of(), false, false, false, false), raw -> {
            JSONArray nodes = new JSONObject(raw).getJSONArray("nodes");
            expect(hasText(nodes, "外尾") && hasText(nodes, "内") && hasText(nodes, "HIDDEN_NODE"));
            for (int i = 0; i < nodes.length(); i++) expect(!"SCRIPT".equals(nodes.getJSONObject(i).getString("tagName")));
        }));
        add("type0 outerHTML 未伪装为可见正文", () -> capture(left, new Request(0, List.of(), false, false, false, true), raw -> {
            String html = new JSONArray(raw).getString(1); expect(html.contains("<script>") && html.contains("HIDDEN_NODE"));
        }));
        for (int type : new int[]{1, 2}) add("旧节点协议 type" + type, () -> capture(left, new Request(type, List.of(), false, false, false, true), raw -> {
            JSONArray a = new JSONArray(raw); expect(a.length() > 9 && a.getString(2).equals(DETAIL));
        }));
        add("加载支付宝 hash 页面", () -> load(left, page("different-wechat-route", "<div>支付宝测试商品</div>", true), "#" + DETAIL + "?orderId=fixture"));
        add("支付宝 route 与 AppID 使用各自字段", () -> capture(left, Request.content(List.of(DETAIL), true, false, false), raw -> {
            JSONArray a = new JSONArray(raw); expect(a.getString(2).equals(DETAIL) && a.getString(4).equals("202100fixture"));
            expect(a.getString(1).contains("支付宝测试商品"));
        }));
        add("支付宝主 URL 已有问号的原边界", () -> load(left, page("unused", "<div>ALIPAY_EDGE</div>", true), "?main=1#" + DETAIL + "?local=2"));
        add("保留原 hash 算法，不偷偷规范化查询串", () -> capture(left, Request.content(List.of(DETAIL), true, false, false), raw -> {
            JSONArray a = new JSONArray(raw); expect(a.getString(2).equals(DETAIL + "?local=2") && a.getString(1).isEmpty());
        }));

        add("左 WebView 加载列表", () -> load(left, page(LIST, "<div>列表商品</div>", false), ""));
        add("右 WebView 加载详情", () -> load(right, page(DETAIL, "<div>详情商品</div>", false), ""));
        add("两个真 WebView：列表→详情的原合并", () -> multi(false));
        add("两个真 WebView：详情→列表的原回退保护", () -> multi(true));
        if (originalMode) originalReaderSteps();
        selectionSteps();
        protectionSteps();
    }

    private void originalReaderSteps() {
        add("原 DEX 正向选择：原生可见性与两个真实 WebView", () -> {
            var result = originalReader.select(List.of(left, right), selection(false, false, false, List.of()));
            expect(result.status() == ColorOsWebViewSelector.Status.OK && result.views().equals(List.of(left, right)));
            done.run();
        });
        add("原 DEX 逆向选择：只读取最后一个真实 WebView", () -> {
            var result = originalReader.select(List.of(left, right), selection(true, false, false, List.of()));
            expect(result.views().equals(List.of(right))); done.run();
        });
        add("原 Reflect：父类私有读取方法与混淆回调名", () -> {
            View inherited = new InheritedPrivateWebView(right);
            var selected = originalReader.select(List.of(inherited), selection(true, true, false, List.of()));
            expect(selected.views().equals(List.of(inherited)));
            capture(inherited, content(false, false), raw -> {
                JSONArray row = new JSONArray(raw);
                expect(row.getString(2).equals(DETAIL) && row.getString(1).equals("详情商品"));
            });
        });
        add("原 DEX 选择接 OS4 Activity token 窗口，真实 DOM 返回", () -> {
            Runnable completion = done;
            ColorOsActivityCapture.request(this, client, selection(true, false, false, List.of()), content(false, false),
                    () -> running, result -> safely(() -> {
                        expect(result.status() == Status.OK);
                        JSONArray row = new JSONArray(result.raw());
                        expect(row.getString(2).equals(DETAIL) && row.getString(1).equals("详情商品"));
                        completion.run();
                    }));
        });
    }
    public interface RenamedCallback { void q(String value); }
    private class PrivateReaderBase extends View {
        private final WebView renderer;
        PrivateReaderBase(WebView renderer) { super(DomAuditActivity.this); this.renderer = renderer; }
        @SuppressWarnings("unused")
        private void evaluateJavascript(String script, RenamedCallback callback) {
            renderer.evaluateJavascript(script, callback::q);
        }
    }
    private final class InheritedPrivateWebView extends PrivateReaderBase {
        InheritedPrivateWebView(WebView renderer) { super(renderer); }
    }

    // Traversal tests supply explicit visibility; they do not claim hidden-API
    // access or Activity-token enumeration has already been solved on OS4.
    private ColorOsWebViewSelector.Options selection(boolean last, boolean ignore, boolean group, List<String> classes) {
        return new ColorOsWebViewSelector.Options(classes, "", last, ignore, group);
    }
    private void selectionSteps() {
        add("OS4 窗口适配：Activity token 隔离与附属窗口", () -> {
            IBinder owner = new Binder(), foreign = new Binder(), window = new Binder();
            TokenView root = new TokenView(owner, window, 2);
            TokenView child = new TokenView(window, new Binder(), 1002);
            TokenView other = new TokenView(foreign, new Binder(), 2);
            TokenView otherChild = new TokenView(other.getWindowToken(), new Binder(), 1002);
            TokenView orphan = new TokenView(null, new Binder(), 2);
            List<View> all = List.of(other, root, child, otherChild, orphan);
            expect(ColorOsActivityCapture.filterSnapshot(all, owner).equals(List.of(root, child)));
            expect(ColorOsActivityCapture.filterSnapshot(all, foreign).equals(List.of(other, otherChild)));
            expect(ColorOsActivityCapture.filterSnapshot(all, null).isEmpty()); done.run();
        });
        add("OS4 窗口适配：仅原版 1000～1999 子窗口区间", () -> {
            IBinder owner = new Binder(), window = new Binder();
            TokenView root = new TokenView(owner, window, 2);
            TokenView lower = new TokenView(window, new Binder(), 1000), upper = new TokenView(window, new Binder(), 1999);
            TokenView tooLow = new TokenView(window, new Binder(), 999), tooHigh = new TokenView(window, new Binder(), 2000);
            TokenView grandchild = new TokenView(lower.getWindowToken(), new Binder(), 1002);
            expect(ColorOsActivityCapture.filterSnapshot(List.of(root, tooLow, lower, upper, tooHigh, grandchild), owner)
                    .equals(List.of(root, lower, upper))); done.run();
        });
        add("原选择器：按窗口逆序只取最后 WebView", () -> {
            var result = ColorOsWebViewSelector.select(List.of(left, right), selection(true, false, false, List.of()), v -> true);
            expect(result.views().equals(List.of(right))); done.run();
        });
        add("原选择器：正向保留两个窗口的顺序", () -> {
            var result = ColorOsWebViewSelector.select(List.of(left, right), selection(false, false, false, List.of()), v -> true);
            expect(result.views().equals(List.of(left, right))); done.run();
        });
        add("原选择器：隐藏的末尾窗口不能抢占前窗口", () -> {
            var result = ColorOsWebViewSelector.select(List.of(left, right), selection(true, false, false, List.of()), v -> v != right);
            expect(result.views().equals(List.of(left))); done.run();
        });
        add("原选择器：忽略可见性只影响逆序路径", () -> {
            var reverse = ColorOsWebViewSelector.select(List.of(left, right), selection(true, true, false, List.of()), null);
            var forward = ColorOsWebViewSelector.select(List.of(left, right), selection(false, true, false, List.of()), v -> v != right);
            expect(reverse.views().equals(List.of(right)) && forward.views().equals(List.of(left))); done.run();
        });
        add("原选择器：子树反向、父 WebView 与 enableWebGroup", () -> {
            LinearLayout parent = new LinearLayout(this);
            FakeView first = new FakeView(0), last = new FakeView(0);
            parent.addView(first); parent.addView(last);
            List<String> childClass = List.of(FakeView.class.getName());
            var reverse = ColorOsWebViewSelector.select(List.of(parent), selection(true, false, false, childClass), v -> true);
            expect(reverse.views().equals(List.of(last)));
            List<String> groupClass = List.of(parent.getClass().getName(), FakeView.class.getName());
            var stop = ColorOsWebViewSelector.select(List.of(parent), selection(false, false, false, groupClass), v -> true);
            var expand = ColorOsWebViewSelector.select(List.of(parent), selection(false, false, true, groupClass), v -> true);
            var reverseParent = ColorOsWebViewSelector.select(List.of(parent), selection(true, false, true, groupClass), v -> true);
            expect(stop.views().equals(List.of(parent)) && expand.views().equals(List.of(parent, first, last))
                    && reverseParent.views().equals(List.of(parent))); done.run();
        });
        add("原选择器：隐藏父节点剪枝、非 WebView 方法不冒认", () -> {
            LinearLayout parent = new LinearLayout(this); FakeView child = new FakeView(0); parent.addView(child);
            var hidden = ColorOsWebViewSelector.select(List.of(parent), selection(false, false, false, List.of(FakeView.class.getName())), v -> v != parent);
            var noName = ColorOsWebViewSelector.select(List.of(child), selection(false, false, false, List.of()), v -> true);
            expect(hidden.status() == ColorOsWebViewSelector.Status.NO_MATCH && noName.status() == ColorOsWebViewSelector.Status.NO_MATCH); done.run();
        });
        add("原选择器：WebView 命名与异形接口", () -> {
            DelayedWebView named = new DelayedWebView("[]");
            var result = ColorOsWebViewSelector.select(List.of(named), selection(false, false, false, List.of()), v -> true);
            expect(result.views().equals(List.of(named))); done.run();
        });
        add("原选择器：可见性缺失和异常不降级猜测", () -> {
            var unavailable = ColorOsWebViewSelector.select(List.of(left), selection(false, false, false, List.of()), null);
            var exception = ColorOsWebViewSelector.select(List.of(left), selection(false, false, false, List.of()), v -> { throw new IllegalStateException(); });
            expect(unavailable.status() == ColorOsWebViewSelector.Status.UNSUPPORTED && exception.views().isEmpty()); done.run();
        });
        add("原选择器：超量窗口拒绝整批不截断", () -> {
            var result = ColorOsWebViewSelector.select(java.util.Collections.nCopies(65, left), selection(false, false, false, List.of()), v -> true);
            expect(result.status() == ColorOsWebViewSelector.Status.LIMIT && result.views().isEmpty()); done.run();
        });
        add("原选择器→真 DOM：只读详情而非混入订单列表", () -> {
            var selected = ColorOsWebViewSelector.select(List.of(left, right), selection(true, false, false, List.of()), v -> true);
            Runnable completion = done;
            client.requestBatch(selected.views(), "", content(false, false), () -> running, result -> safely(() -> {
                expect(result.status() == Status.OK);
                var parsed = ColorOsWebTextParser.parse(result.raw(), "", POLICY);
                expect(parsed.state().texts().equals(List.of("详情商品")) && parsed.state().route.equals(DETAIL)); completion.run();
            }));
        });
        add("原批次：保留回调到达顺序而非请求顺序", () -> {
            DelayedWebView first = new DelayedWebView("[\"first\"]"), second = new DelayedWebView("[\"second\"]");
            Runnable completion = done;
            client.requestBatch(List.of(first, second), "", Request.pageId(), () -> running, result -> safely(() -> {
                expect(result.status() == Status.OK);
                JSONArray batch = new JSONArray(result.raw());
                expect(batch.getString(0).equals(second.raw) && batch.getString(1).equals(first.raw)); completion.run();
            }));
            second.reply(); first.reply();
        });
        add("原批次：重复回调不污染结果", () -> {
            Runnable completion = done;
            client.requestBatch(List.of(new FakeView(3), new FakeView(3)), "", Request.pageId(), () -> running, result -> safely(() -> {
                expect(result.status() == Status.OK && new JSONArray(result.raw()).length() == 2); completion.run();
            }));
        });
        add("批次保护：子项异常拒绝整批", () -> {
            Runnable completion = done;
            client.requestBatch(List.of(new FakeView(0), new FakeView(1)), "", Request.pageId(), () -> running, result -> safely(() -> {
                expect(result.status() == Status.FAILED && result.raw().isEmpty()); completion.run();
            }));
        });
        add("批次保护：取消后晚回调不复活", () -> {
            DelayedWebView first = new DelayedWebView("[]"), second = new DelayedWebView("[]");
            int[] count = {0}; Status[] status = {null}; Runnable completion = done;
            var ticket = client.requestBatch(List.of(first, second), "", Request.pageId(), () -> true,
                    result -> { count[0]++; status[0] = result.status(); });
            ticket.cancel(); first.reply(); second.reply();
            main.post(() -> safely(() -> { expect(count[0] == 1 && status[0] == Status.CANCELLED); completion.run(); }));
        });
        add("批次保护：超时不发送半张页面", () -> {
            ColorOsWebViewClient shortClient = new ColorOsWebViewClient(50, originalReader);
            DelayedWebView late = new DelayedWebView("[]");
            int[] count = {0}; Status[] status = {null}; Runnable completion = done;
            shortClient.requestBatch(List.of(new FakeView(0), late), "", Request.pageId(), () -> true,
                    result -> { count[0]++; status[0] = result.status(); });
            main.postDelayed(() -> safely(() -> {
                late.reply(); expect(count[0] == 1 && status[0] == Status.TIMEOUT); shortClient.close(); completion.run();
            }), 180);
        });
    }
    public final class DelayedWebView extends View {
        final String raw;
        ValueCallback<String> callback;
        DelayedWebView(String raw) { super(DomAuditActivity.this); this.raw = raw; }
        public void evaluateJavascript(String script, ValueCallback<String> callback) { this.callback = callback; }
        void reply() { if (callback != null) callback.onReceiveValue(raw); }
    }
    public final class TokenView extends View {
        final IBinder window;
        TokenView(IBinder owner, IBinder window, int type) {
            super(DomAuditActivity.this); this.window = window;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(); params.token = owner; params.type = type;
            setLayoutParams(params);
        }
        @Override public IBinder getWindowToken() { return window; }
    }
    private static boolean hasText(JSONArray nodes, String text) throws Exception {
        for (int i = 0; i < nodes.length(); i++) if (text.equals(nodes.getJSONObject(i).optString("text"))) return true;
        return false;
    }
    private void multi(boolean reverse) {
        Runnable completion = done;
        int generation = epoch;
        client.request(reverse ? right : left, "", content(false, false), () -> running && epoch == generation, a -> safely(() -> {
            expect(a.status() == Status.OK);
            client.request(reverse ? left : right, "", content(false, false), () -> running && epoch == generation, b -> safely(() -> {
                expect(b.status() == Status.OK);
                String batch = new JSONArray().put(a.raw()).put(b.raw()).toString();
                var merged = ColorOsWebTextParser.parse(batch, "", POLICY);
                expect(merged.state().route.equals(DETAIL));
                expect(merged.state().texts().equals(reverse ? List.of("详情商品") : List.of("列表商品", "详情商品")));
                completion.run();
            }));
        }));
    }

    // Explicit fake adapters exercise transport failures; the earlier cases use real renderers.
    public interface Obfuscated { void a(Object value); }
    public interface Alternative { void b(String value); }
    public final class FakeView extends View {
        int mode, calls;
        FakeView(int mode) { super(DomAuditActivity.this); this.mode = mode; }
        public void evaluateJavascript(String script, ValueCallback<String> callback) {
            calls++;
            switch (mode) {
                case 1 -> throw new IllegalStateException("FixtureOnly");
                case 2 -> callback.onReceiveValue(null);
                case 3 -> { callback.onReceiveValue("[]"); callback.onReceiveValue("[]"); }
                case 4 -> main.postDelayed(() -> callback.onReceiveValue("[]"), 120);
                case 5 -> callback.onReceiveValue("x".repeat(1_048_577));
                default -> callback.onReceiveValue("[]");
            }
        }
    }
    public final class ObfuscatedView extends View {
        ObfuscatedView() { super(DomAuditActivity.this); }
        public void z(String script, Obfuscated callback) {
            callback.toString(); callback.hashCode(); callback.equals(callback);
            callback.a("[]");
        }
    }
    public final class AmbiguousView extends View {
        AmbiguousView() { super(DomAuditActivity.this); }
        public void evaluateJavascript(String s, Obfuscated c) { throw new AssertionError("WrongOverload"); }
        public void evaluateJavascript(String s, Alternative c) { throw new AssertionError("WrongOverload"); }
    }
    private void expectResult(View view, String method, Status status) {
        Runnable completion = done;
        client.request(view, method, Request.pageId(), () -> true, result -> safely(() -> {
            expect(result.status() == status); completion.run();
        }));
    }
    private void protectionSteps() {
        add("null View 安全拒绝", () -> expectResult(null, "", Status.UNSUPPORTED));
        add("调用异常被隔离", () -> expectResult(new FakeView(1), "", Status.FAILED));
        add("null 回调不当作成功", () -> expectResult(new FakeView(2), "", Status.FAILED));
        add("超大回调拒绝", () -> expectResult(new FakeView(5), "", Status.LIMIT));
        add("混淆方法名与异形回调接口", () -> expectResult(new ObfuscatedView(), "z", Status.OK));
        add("歧义重载不任意选择", () -> expectResult(new AmbiguousView(), "", Status.UNSUPPORTED));
        add("重复回调只完成一次", () -> {
            int[] calls = {0}; Runnable completion = done;
            client.request(new FakeView(3), "", Request.pageId(), () -> true, result -> calls[0]++);
            main.post(() -> safely(() -> { expect(calls[0] == 1); completion.run(); }));
        });
        add("失效页面不执行采集", () -> {
            FakeView view = new FakeView(0); Runnable completion = done;
            client.request(view, "", Request.pageId(), () -> false, r -> safely(() -> {
                expect(r.status() == Status.STALE && view.calls == 0); completion.run();
            }));
        });
        add("请求后切页，丢弃旧回调", () -> {
            AtomicBoolean current = new AtomicBoolean(true); Runnable completion = done;
            client.request(new FakeView(4), "", Request.pageId(), current::get, r -> safely(() -> {
                expect(r.status() == Status.STALE && r.raw().isEmpty()); completion.run();
            }));
            current.set(false);
        });
        add("超时后迟到回调不重新投递", () -> {
            ColorOsWebViewClient shortClient = new ColorOsWebViewClient(50, originalReader);
            int[] calls = {0}; Status[] received = {null}; Runnable completion = done;
            shortClient.request(new FakeView(4), "", Request.pageId(), () -> true, r -> { calls[0]++; received[0] = r.status(); });
            main.postDelayed(() -> safely(() -> {
                expect(calls[0] == 1 && received[0] == Status.TIMEOUT); shortClient.close(); completion.run();
            }), 240);
        });
        add("显式取消不投递旧数据", () -> {
            int[] calls = {0}; Status[] received = {null}; Runnable completion = done;
            var ticket = client.request(new FakeView(4), "", Request.pageId(), () -> true, r -> { calls[0]++; received[0] = r.status(); });
            ticket.cancel();
            main.postDelayed(() -> safely(() -> { expect(calls[0] == 1 && received[0] == Status.CANCELLED); completion.run(); }), 200);
        });
        add("已关闭客户端保持停止", () -> {
            ColorOsWebViewClient closed = new ColorOsWebViewClient(5_000, originalReader); closed.close(); Runnable completion = done;
            closed.request(new FakeView(0), "", Request.pageId(), () -> true, r -> safely(() -> {
                expect(r.status() == Status.CANCELLED); completion.run();
            }));
        });
        add("调用方回调抛异常不击穿 UI 线程", () -> {
            Runnable completion = done;
            client.request(new FakeView(0), "", Request.pageId(), () -> true, r -> { throw new IllegalStateException("FixtureCallback"); });
            main.post(completion);
        });
        add("变更页面的 type6 不进入只读采集接口", () -> {
            Runnable completion = done;
            client.request(left, "", new Request(6, List.of(), false, false, false, true), () -> true, r -> safely(() -> {
                expect(r.status() == Status.UNSUPPORTED); completion.run();
            }));
        });
    }
}
