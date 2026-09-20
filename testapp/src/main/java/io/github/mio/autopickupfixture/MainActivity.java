package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(34), dp(22), dp(34));
        body.setBackgroundColor(Color.rgb(246, 246, 246));

        TextView title = text("取餐码自动上岛 · 测试台", 25, Color.rgb(25, 25, 25));
        title.setPadding(0, 0, 0, dp(10));
        body.addView(title);
        TextView intro = text("本 App、微信、QQ、支付宝不加入 Hook 作用域。模块作用域为 MIUIAICR、超级小爱、小米 AI 通话服务及 SystemUI（仅食物动画）。品牌模拟与真实页面采集分开验证，每个常规正例约 2.5 秒后出码。", 15, Color.DKGRAY);
        intro.setPadding(0, 0, 0, dp(20));
        body.addView(intro);

        add(body, "原生页 · 蜜雪奶茶模型", TeaPickupActivity.class);
        add(body, "原生页 · 星巴克咖啡模型", CoffeePickupActivity.class);
        add(body, "原生页 · 肯德基餐饮模型", CateringPickupActivity.class);
        add(body, "WebView · 小程序变量与 DOM 更新", WebPickupActivity.class);
        add(body, "全部品牌规则 / 全部模型 / 参数化场景", CatalogActivity.class);
        add(body, "原版 DOM / 路径 / PCR / 异常保护回归（不发卡）", DomAuditActivity.class);
        add(body, "系统 UIAgent → 原 DOM 协议（仅测试进程）", UiAgentDomFixtureActivity.class);

        TextView checklist = text("检查项\n① 自动出现超级岛和静默通知\n② 相同码重复改写不重复上岛\n③ 换新码后允许更新\n④ 点通知、展开卡片或岛均回到原测试页\n⑤ WebView 的错误 origin / 黑名单路径不触发", 14, Color.GRAY);
        checklist.setPadding(0, dp(20), 0, 0);
        body.addView(checklist);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
    }

    private void add(LinearLayout parent, String label, Class<? extends Activity> type) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(v -> startActivity(new Intent(this, type)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        parent.addView(button, params);
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
}
