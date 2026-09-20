package io.github.mio.autopickupfixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

@SuppressLint("SetTextI18n")
abstract class NativePickupActivity extends Activity {
    record Spec(String brand, String product, String firstCode, int accent) { }

    private TextView stateView;
    private TextView codeView;
    private TextView returnView;
    private int sequence;
    private ImageView imageCode;

    abstract Spec spec();
    String codeLabel() { return "取餐码"; }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
        showReturn(getIntent());
        int mode = getIntent().getIntExtra("fixture_mode", 0);
        if (mode != 3) codeView.postDelayed(this::reveal, mode == 1 ? 0L : mode == 2 ? 12_000L : 2_500L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("mio_from_island", false) && getIntent() != null
                && !intent.hasExtra("fixture_entry")) intent.putExtras(getIntent());
        setIntent(intent);
        showReturn(intent);
    }

    private void build() {
        Spec spec = spec();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(30), dp(22), dp(30));
        body.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView brand = text(spec.brand(), 26, spec.accent());
        body.addView(brand);
        TextView hint = text("原生 View 树正例 · 页面内事件驱动提取", 14, Color.GRAY);
        hint.setPadding(0, dp(4), 0, dp(24));
        body.addView(hint);

        stateView = text("订单状态：门店制作中", 18, Color.DKGRAY);
        body.addView(stateView);
        codeView = text("请稍候，暂未生成号码", 34, Color.rgb(35, 35, 35));
        codeView.setPadding(0, dp(20), 0, dp(12));
        body.addView(codeView);
        imageCode = new ImageView(this);
        imageCode.setVisibility(android.view.View.GONE);
        imageCode.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(imageCode, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(95)));
        TextView product = text("商品：" + spec.product(), 17, Color.DKGRAY);
        product.setPadding(0, 0, 0, dp(20));
        body.addView(product);
        TextView store = text("门店：理工大学东区1公寓店", 16, Color.GRAY);
        store.setPadding(0, 0, 0, dp(20));
        body.addView(store);

        addButton(body, "立即显示", v -> reveal());
        addButton(body, "重复写入（应去重）", v -> reveal());
        addButton(body, "切换新码（应更新）", v -> nextCode());
        addButton(body, "恢复制作中（不应新增）", v -> hideCode());

        returnView = text("尚未通过岛或通知返回", 14, Color.GRAY);
        returnView.setPadding(0, dp(18), 0, 0);
        body.addView(returnView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
    }

    private void reveal() {
        stateView.setText("订单状态：制作完成，等待取餐");
        if (getIntent().getIntExtra("fixture_mode", 0) == 4) {
            codeView.setText("取餐凭证见下图");
            Bitmap bitmap = Bitmap.createBitmap(800, 190, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(68);
            canvas.drawText("取餐码 " + currentCode(), 20, 110, paint);
            imageCode.setImageBitmap(bitmap);
            imageCode.setVisibility(android.view.View.VISIBLE);
            return;
        }
        codeView.setText(codeLabel() + "\n" + currentCode() + "\n请凭号码到柜台取餐");
    }

    private void nextCode() {
        sequence++;
        reveal();
    }

    private void hideCode() {
        imageCode.setVisibility(android.view.View.GONE);
        stateView.setText("订单状态：门店制作中");
        codeView.setText("请稍候，暂未生成号码");
    }

    private String currentCode() {
        if (sequence == 0) return spec().firstCode();
        String first = spec().firstCode();
        if (first.matches("[0-9]{1,6}\\..+")) {
            int dot = first.indexOf('.');
            return (Integer.parseInt(first.substring(0, dot)) + sequence) + first.substring(dot);
        }
        if (first.matches("\\d{4}")) {
            return String.format(java.util.Locale.ROOT, "%04d",
                    (Integer.parseInt(first) + sequence) % 10_000);
        }
        char prefix = Character.isLetter(first.charAt(0)) ? first.charAt(0) : 'T';
        return prefix + String.valueOf(700 + sequence);
    }

    private void showReturn(Intent intent) {
        if (returnView == null) return;
        if (intent != null && intent.getBooleanExtra("mio_from_island", false)) {
            returnView.setText("✓ 已由超级岛/通知精确返回本页");
            returnView.setTextColor(Color.rgb(0, 120, 70));
        }
    }

    private void addButton(LinearLayout parent, String label, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        parent.addView(button, params);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
