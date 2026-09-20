package io.github.mio.autopickupisland;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit fixture-only simulation catalog, rebuilt from the currently applied XML.
 * This is not a claim that the corresponding real apps/DOM collectors pass an end-to-end test. */
final class FixtureCatalog {
    static final String ACTIVITY = Constants.PKG_TEST_FIXTURE + ".ScenarioPickupActivity";
    static final String LABEL_PREFIX = "MIOFixture:";
    private static RuleRepository owner;
    private static Map<String, Rule> cases;
    private static String json;

    static synchronized String json(Context context) throws Exception {
        ensure(context);
        return json;
    }
    static synchronized Rule match(Context context, String packageName, String activity, String label) throws Exception {
        if (!Constants.PKG_TEST_FIXTURE.equals(packageName) || !ACTIVITY.equals(activity)
                || !label.startsWith(LABEL_PREFIX)) return null;
        ensure(context);
        return cases.get(label.substring(LABEL_PREFIX.length()));
    }
    private static void ensure(Context context) throws Exception {
        RuleRepository repo = RuleRepository.get(context);
        if (repo == owner && cases != null) return;
        Map<String, Rule> next = new LinkedHashMap<>();
        JSONArray entries = new JSONArray();
        for (Rule original : repo.allRules()) {
            if (Constants.PKG_TEST_FIXTURE.equals(original.packageName)) continue;
            String identity = original.packageName + '|' + original.originId + '|' + original.category;
            for (Rule.Target target : original.activities) identity += "|a:" + target.value;
            for (Rule.Target target : original.filterPaths) identity += "|p:" + target.value;
            String key = key(identity);
            Rule rule = copy(original, key);
            next.put(key, rule);
            entries.put(entry(key, rule, "品牌/规则模拟", original.packageName
                    + (original.webView ? " · 小程序（模拟，不是实际 DOM 验证）" : " · 原生页规则模拟")));
        }
        String[] assets = ModuleAccess.context(context).getAssets().list("coloros/pickupcode");
        if (assets != null) for (String asset : assets) {
            if (!asset.matches("base_bg_[a-z0-9_]+\\.webp")) continue;
            String key = key("model:" + asset);
            Rule original = new Rule();
            original.brandName = "模型预览";
            original.appName = asset;
            original.category = asset.contains("coffee") ? "coffee" : asset.contains("takeout") ? "catering" : "tea";
            original.baseStyle = asset;
            original.pickupButtonColor = "#3482FF";
            original.pickupColor = "#3482FF";
            Rule rule = copy(original, key);
            next.put(key, rule);
            entries.put(entry(key, rule, "全部模型", asset));
        }
        json = entries.toString();
        cases = next;
        owner = repo;
    }
    private static JSONObject entry(String key, Rule rule, String group, String description) throws Exception {
        return new JSONObject().put("id", key).put("brand", rule.brandName)
                .put("category", rule.category).put("group", group).put("description", description)
                .put("color", rule.pickupButtonColor).put("model", rule.baseStyle);
    }
    private static Rule copy(Rule source, String key) {
        Rule rule = new Rule();
        rule.packageName = Constants.PKG_TEST_FIXTURE;
        rule.originId = "fixture_" + key; // Isolates each case's dedupe/navigation identity.
        rule.category = source.category;
        rule.label = source.label;
        rule.appName = source.appName;
        rule.brandName = source.brandName.isEmpty() ? source.appName : source.brandName;
        rule.tagAppName = source.tagAppName;
        rule.logo = source.logo;
        rule.sticker = source.sticker;
        rule.baseStyle = source.baseStyle;
        rule.aodImage = source.aodImage;
        rule.pickupButtonColor = source.pickupButtonColor;
        rule.pickupColor = source.pickupColor;
        rule.cardColor = source.cardColor;
        rule.cardAlpha = source.cardAlpha;
        rule.launchPath = ACTIVITY;
        Rule.Target target = new Rule.Target();
        target.value = ACTIVITY;
        target.useCloud = false; // A synthetic fixture never opts into remote processing.
        rule.activities.add(target);
        return rule;
    }
    private static String key(String identity) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
        return out.toString();
    }
    private FixtureCatalog() { }
}
