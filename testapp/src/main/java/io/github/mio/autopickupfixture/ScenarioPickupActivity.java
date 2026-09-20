package io.github.mio.autopickupfixture;

import android.app.ActivityManager;
import android.graphics.Color;
import android.os.Bundle;
import org.json.JSONObject;

public final class ScenarioPickupActivity extends NativePickupActivity {
    private JSONObject entry;
    @Override protected void onCreate(Bundle state) {
        try { entry = new JSONObject(getIntent().getStringExtra("fixture_entry")); }
        catch (Exception error) { entry = new JSONObject(); }
        super.onCreate(state);
        String id = entry.optString("id");
        if (!id.matches("[a-f0-9]{24}")) { finish(); return; }
        setTaskDescription(new ActivityManager.TaskDescription("MIOFixture:" + id));
    }
    @Override Spec spec() {
        if (entry == null) return new Spec("场景测试", "", "0521", Color.BLUE);
        int color = Color.BLUE;
        try { color = Color.parseColor(entry.optString("color")); } catch (Exception ignored) { }
        String product = getIntent().getStringExtra("fixture_product");
        String code = getIntent().getStringExtra("fixture_code");
        return new Spec(entry.optString("brand"), product == null ? "测试商品" : product,
                code == null || code.isEmpty() ? "0521" : code, color);
    }
}
