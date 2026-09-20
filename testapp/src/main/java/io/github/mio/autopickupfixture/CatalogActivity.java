package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import org.json.*;

/** Reads the module's live catalog; no source-app injection or production identity spoofing. */
public final class CatalogActivity extends Activity {
    static final Uri PROVIDER = Uri.parse("content://io.github.mio.autopickupisland.bridge");
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(36, 90, 36, 50);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
        TextView heading = new TextView(this);
        heading.setText("全部规则与模型 · 模拟测试\n每个品牌/模型都可选号码、商品和延迟。只在测试包内模拟，不能替代真实小程序 DOM、微信跳转及远端服务的验证。云白名单本身不提供页面或模型。\n");
        heading.setTextSize(17);
        body.addView(heading);
        load(body, heading, 0);
    }
    private void load(LinearLayout body, TextView heading, int attempt) {
        if (isFinishing() || isDestroyed()) return;
        try {
            Bundle bundle = getContentResolver().call(PROVIDER, "fixture_catalog", null, null);
            if (bundle == null || !bundle.containsKey("cases")) throw new IllegalStateException("请先更新模块");
            JSONArray cases = new JSONArray(bundle.getString("cases"));
            for (int index = 0; index < cases.length(); index++) {
                JSONObject entry = cases.getJSONObject(index);
                Button button = new Button(this);
                button.setAllCaps(false);
                button.setText(entry.getString("group") + " · " + entry.getString("brand") + "\n" + entry.getString("description"));
                button.setOnClickListener(v -> configure(entry));
                body.addView(button);
            }
            JSONArray cloud = new JSONArray(bundle.getString("cloud", "[]"));
            TextView inventory = new TextView(this);
            StringBuilder info = new StringBuilder("\n云端登记清单（不是已验证场景）\n");
            for (int i = 0; i < cloud.length(); i++) {
                JSONObject entry = cloud.getJSONObject(i);
                info.append(entry.optString("name")).append(entry.optBoolean("disabled") ? " · 云端禁用" : " · 云端登记")
                        .append("\n");
            }
            inventory.setText(info);
            inventory.setTextSize(14);
            body.addView(inventory);
            heading.append("已加载 " + cases.length() + " 个模拟入口，云登记 " + cloud.length() + " 项。\n");
        } catch (Exception error) {
            if (attempt < 3 && error instanceof IllegalArgumentException
                    && String.valueOf(error.getMessage()).contains("Unknown authority")) {
                // Package replacement/provider registration can briefly race first launch.
                heading.postDelayed(() -> load(body, heading, attempt + 1),
                        new long[]{400L, 1_000L, 2_000L}[attempt]);
                return;
            }
            heading.append("\n加载失败：" + error.getMessage() + "\n可先打开模块设置，再点重新加载。");
            Button retry = new Button(this);
            retry.setText("重新加载");
            retry.setOnClickListener(v -> recreate());
            body.addView(retry);
        }
    }
    private void configure(JSONObject entry) {
        LinearLayout form = new LinearLayout(this);
        form.setPadding(40, 10, 40, 10);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText product = new EditText(this);
        product.setHint("商品名：可填很长的名称检查滚动/截断");
        String category = entry.optString("category");
        product.setText(category.equals("coffee") ? "冰拿铁" : category.equals("catering") ? "香辣鸡腿堡套餐" : "芒果冰奶");
        form.addView(product);
        EditText code = new EditText(this);
        code.setHint("取餐码（保留前导零）");
        code.setText("0521");
        form.addView(code);
        Spinner mode = new Spinner(this);
        String[] modes = {"延迟 2.5 秒出码", "进入立即出码", "延迟 12 秒出码", "不出码（反例）", "仅图片出码（OCR 兜底专项）"};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        form.addView(mode);
        new AlertDialog.Builder(this).setTitle(entry.optString("brand") + " · 测试参数")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("开始", (dialog, which) -> {
                    String value = code.getText().toString().trim();
                    if (value.length() > 24 || product.length() > 160) {
                        Toast.makeText(this, "测试输入过长", Toast.LENGTH_SHORT).show(); return;
                    }
                    startActivity(new Intent(this, ScenarioPickupActivity.class)
                            .putExtra("fixture_entry", entry.toString())
                            .putExtra("fixture_code", value).putExtra("fixture_product", product.getText().toString())
                            .putExtra("fixture_mode", mode.getSelectedItemPosition()));
                }).show();
    }
}
