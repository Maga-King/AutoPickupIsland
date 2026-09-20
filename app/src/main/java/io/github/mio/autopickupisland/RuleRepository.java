package io.github.mio.autopickupisland;

import android.content.Context;
import android.os.Bundle;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RuleRepository {
    private static volatile RuleRepository instance;

    private final List<Rule> rules;
    private final Set<String> packages;
    private final Map<String, String> cloudNamesByOrigin;
    private final Set<String> cloudDisabledOrigins;
    private final String version;
    private final ColorOsRecognitionPolicy recognitionPolicy;
    private final long revision;

    private RuleRepository(Context host, long revision) throws Exception {
        this.revision = revision;
        Context module = ModuleAccess.context(host);
        String overlay = readOverlay(host);
        ParseResult parsed = null;
        if (!overlay.isEmpty()) {
            try {
                parsed = parse(new ByteArrayInputStream(overlay.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
            }
        }
        if (parsed == null || parsed.rules.isEmpty()) {
            try (InputStream input = module.getAssets().open(Constants.RULE_ASSET)) {
                parsed = parse(input);
            }
        }
        // The fixture rules are deliberately kept in a separate asset. A ColorOS RUS overlay can
        // replace the production list without making the module's end-to-end self-test disappear.
        try (InputStream input = module.getAssets().open(Constants.TEST_RULE_ASSET)) {
            ParseResult fixture = parse(input);
            for (Rule candidate : fixture.rules) {
                if (!containsSameRule(parsed.rules, candidate)) parsed.rules.add(candidate);
            }
        }
        rules = Collections.unmodifiableList(parsed.rules);
        // Fixture XML is appended above; its default root flags must not replace production RUS.
        recognitionPolicy = parsed.policy;
        HashSet<String> packageNames = new HashSet<>();
        for (Rule rule : rules) packageNames.add(rule.packageName);
        packages = Collections.unmodifiableSet(packageNames);
        CloudPolicy cloudPolicy = readCloudPolicy(host);
        cloudNamesByOrigin = cloudPolicy.namesByOrigin;
        cloudDisabledOrigins = cloudPolicy.disabledOrigins;
        version = parsed.version.isEmpty() ? Constants.BUILTIN_RULE_VERSION : parsed.version;
    }

    static RuleRepository get(Context context) {
        long revision = readRevision(context);
        RuleRepository current = instance;
        if (current != null && (revision < 0 || current.revision == revision)) return current;
        synchronized (RuleRepository.class) {
            current = instance;
            if (current == null || revision >= 0 && current.revision != revision) {
                try {
                    Context application = context.getApplicationContext();
                    current = new RuleRepository(application == null ? context : application, revision);
                } catch (Exception e) {
                    throw new IllegalStateException("ColorOS rules unavailable", e);
                }
                instance = current;
            }
        }
        return current;
    }

    static void invalidate() {
        instance = null;
    }

    private static long readRevision(Context context) {
        try {
            Bundle out = context.getContentResolver().call(Constants.PROVIDER_URI, "config", null, null);
            return out == null ? -1L : out.getLong("rules_revision", -1L);
        } catch (Exception ignored) { return -1L; }
    }

    String version() {
        return version;
    }

    int size() {
        return rules.size();
    }

    List<Rule> allRules() { return rules; }

    ColorOsRecognitionPolicy recognitionPolicy() { return recognitionPolicy; }

    Rule recognitionRule(Context context, PickupEvent event) throws Exception {
        if (event == null) return null;
        Rule fixture = FixtureCatalog.match(context, event.sourcePackage, event.activity, event.actualTaskLabel);
        if (fixture != null) return fixture;
        Rule fallback = null;
        for (Rule rule : rules) {
            if (!rule.packageName.equals(event.sourcePackage) || rule.webView != event.webView
                    || !rule.originId.equals(event.ruleOriginId) || !rule.appName.equals(event.ruleAppName)
                    || cloudDisabledOrigins.contains(comparable(rule.originId))) continue;
            if (rule.webView ? rule.pathWhitelisted(event.recognitionPath())
                    : rule.activityWhitelisted(event.activity)) return rule;
            if (fallback == null) fallback = rule;
        }
        return fallback; // Preserve the matched rule even when its real-code page gate will reject.
    }

    boolean hasRules(String packageName) {
        return packages.contains(PickupEvent.clean(packageName));
    }

    Rule matchMini(String packageName, String appId, String route) {
        for (Rule rule : rules) {
            if (!rule.packageName.equals(packageName)) continue;
            if (cloudDisabledOrigins.contains(comparable(rule.originId))) continue;
            if (!rule.originId.isEmpty() && !rule.originMatches(appId)) continue;
            Rule.Target target = rule.pathTarget(route);
            if (target != null) return rule;
        }
        return null;
    }

    Rule.Target pathTarget(Rule rule, String route) {
        return rule == null ? null : rule.pathTarget(route);
    }

    Rule matchNative(String packageName, String activity) {
        for (Rule rule : rules) {
            if (!rule.packageName.equals(packageName)) continue;
            if (rule.activityTarget(activity) != null) return rule;
        }
        return null;
    }

    Rule.Target activityTarget(Rule rule, String activity) {
        return rule == null ? null : rule.activityTarget(activity);
    }

    boolean isKnownOrigin(String packageName, String appId) {
        for (Rule rule : rules) {
            if (rule.packageName.equals(packageName)
                    && !cloudDisabledOrigins.contains(comparable(rule.originId))
                    && rule.originMatches(appId)) return true;
        }
        return false;
    }

    boolean hasWebRules(String packageName) {
        for (Rule rule : rules) {
            if (rule.packageName.equals(packageName) && rule.webView) return true;
        }
        return false;
    }

    boolean hasNativeRules(String packageName) {
        for (Rule rule : rules) {
            if (rule.packageName.equals(packageName) && !rule.activities.isEmpty()) return true;
        }
        return false;
    }

    Rule matchPage(String packageName, String taskLabel, String content) {
        String cleanPackage = PickupEvent.clean(packageName);
        String label = comparable(taskLabel);
        String body = comparable(PickupEvent.truncate(content, Constants.MAX_CONTENT_LENGTH));
        // AssistStructure contains the whole visible WeChat task. Never select a mini-program
        // rule from body text alone: a chat, search result or another mini-program can mention
        // the same brand. ColorOS has an applet-id/path signal; on Xiaomi the task label is the
        // equivalent low-cost, non-invasive scope boundary available to this fallback path.
        boolean requireLabelScope = "com.tencent.mm".equals(cleanPackage);
        Rule best = null;
        int bestScore = 0;
        for (Rule rule : rules) {
            if (!rule.webView || !rule.packageName.equals(cleanPackage)) continue;
            String origin = comparable(rule.originId);
            if (cloudDisabledOrigins.contains(origin)) continue;
            if (!rule.label.equals(taskLabel) && recognitionPolicy.blacklistedLabel(taskLabel)) continue;
            if (requireLabelScope && !labelMatches(rule, label,
                    cloudNamesByOrigin.getOrDefault(origin, ""))) continue;
            int score = score(rule, label, body);
            if (score > bestScore) {
                best = rule;
                bestScore = score;
            }
        }
        return best;
    }

    boolean hasPageScope(String packageName, String taskLabel) {
        String label = comparable(taskLabel);
        for (Rule rule : rules) {
            String origin = comparable(rule.originId);
            if (rule.webView && rule.packageName.equals(packageName)
                    && (rule.label.equals(taskLabel) || !recognitionPolicy.blacklistedLabel(taskLabel))
                    && !cloudDisabledOrigins.contains(origin)
                    && labelMatches(rule, label, cloudNamesByOrigin.getOrDefault(origin, ""))) return true;
        }
        return false;
    }

    private static boolean labelMatches(Rule rule, String label, String cloudName) {
        if (label.isEmpty()) return false;
        return labelContains(label, comparable(rule.label))
                || labelContains(label, comparable(rule.appName))
                || labelContains(label, comparable(rule.brandName))
                || labelContains(label, comparable(cloudName));
    }

    private static boolean labelContains(String label, String candidate) {
        return candidate.length() >= 2 && label.contains(candidate);
    }

    private static int score(Rule rule, String label, String body) {
        int score = 0;
        String configuredLabel = comparable(rule.label);
        String appName = comparable(rule.appName);
        String brandName = comparable(rule.brandName);
        if (!configuredLabel.isEmpty()) {
            if (label.equals(configuredLabel)) score = Math.max(score, 100_000 + configuredLabel.length());
            else if (label.contains(configuredLabel) || configuredLabel.contains(label) && label.length() >= 2) {
                score = Math.max(score, 80_000 + configuredLabel.length());
            }
            if (body.contains(configuredLabel)) score = Math.max(score, 60_000 + configuredLabel.length());
        }
        score = Math.max(score, descriptiveScore(label, body, appName, 40_000));
        score = Math.max(score, descriptiveScore(label, body, brandName, 30_000));
        return score;
    }

    private static int descriptiveScore(String label, String body, String needle, int base) {
        if (needle.length() < 2) return 0;
        if (label.equals(needle)) return base + 20_000 + needle.length();
        if (label.contains(needle)) return base + 10_000 + needle.length();
        return body.contains(needle) ? base + needle.length() : 0;
    }

    private static String comparable(String value) {
        return PickupEvent.clean(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String readOverlay(Context host) {
        try {
            Bundle out = host.getContentResolver().call(
                    Constants.PROVIDER_URI, "rules", null, null);
            return out == null ? "" : out.getString("xml", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static CloudPolicy readCloudPolicy(Context host) {
        try {
            Bundle out = host.getContentResolver().call(
                    Constants.PROVIDER_URI, "cloud_rules", null, null);
            String raw = out == null ? "" : out.getString("whitelist", "");
            if (raw.isEmpty() || raw.length() > 300_000) return CloudPolicy.EMPTY;
            JSONArray entries = new JSONArray(raw);
            HashMap<String, String> names = new HashMap<>();
            HashSet<String> disabled = new HashSet<>();
            int count = Math.min(entries.length(), 500);
            for (int i = 0; i < count; i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) continue;
                String origin = comparable(item.optString("origId"));
                String name = PickupEvent.truncate(PickupEvent.clean(
                        item.optString("name")), 80);
                if (!origin.matches("gh_[a-z0-9]{6,40}") || name.isEmpty()) continue;
                names.put(origin, name);
                if (item.optBoolean("disabled", false)) disabled.add(origin);
            }
            return new CloudPolicy(Collections.unmodifiableMap(names),
                    Collections.unmodifiableSet(disabled));
        } catch (Exception ignored) {
            return CloudPolicy.EMPTY;
        }
    }

    private record CloudPolicy(Map<String, String> namesByOrigin,
                               Set<String> disabledOrigins) {
        private static final CloudPolicy EMPTY = new CloudPolicy(Map.of(), Set.of());
    }

    static boolean looksLikeRuleXml(String xml) {
        if (xml == null || xml.length() < 100 || xml.length() > 500_000) return false;
        try {
            ParseResult result = parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return result.rules.size() >= 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    static long checkedCloudVersion(String xml) throws Exception {
        if (xml == null || xml.length() > 500_000 || xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
            throw new IllegalArgumentException("UnsafeXml");
        XmlPullParser check = Xml.newPullParser();
        check.setInput(new java.io.StringReader(xml));
        if (check.nextTag() != XmlPullParser.START_TAG || !"config".equals(check.getName()))
            throw new IllegalArgumentException("WrongXmlRoot");
        ParseResult parsed = parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        if (parsed.rules.isEmpty() || !parsed.version.matches("[0-9]{1,12}"))
            throw new IllegalArgumentException("InvalidXmlRules");
        return Long.parseLong(parsed.version);
    }

    private static ParseResult parse(InputStream input) throws Exception {
        byte[] bytes = readAll(input);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
        ParseResult result = new ParseResult();
        ColorOsRecognitionPolicy.Builder policy = new ColorOsRecognitionPolicy.Builder();
        Rule current = null;
        String section = "";
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("scan".equals(name)) {
                    current = new Rule();
                    current.packageName = attr(parser, "package_name");
                    current.category = attr(parser, "category");
                    current.label = attr(parser, "label");
                    current.webView = Boolean.parseBoolean(attr(parser, "isWebView"));
                } else if ("filter_paths".equals(name)) {
                    section = "paths";
                } else if ("monitor_activities".equals(name)) {
                    section = "activities";
                } else if ("item".equals(name) && current != null) {
                    Rule.Target target = new Rule.Target();
                    target.useCloud = Boolean.parseBoolean(attr(parser, "useCloud"));
                    target.ignoreVisibility = Boolean.parseBoolean(attr(parser, "ignoreVis"));
                    if ("paths".equals(section)) {
                        target.value = attr(parser, "path");
                        if (!target.value.isEmpty()) current.filterPaths.add(target);
                    } else if ("activities".equals(section)) {
                        target.value = attr(parser, "ac");
                        if (!target.value.isEmpty()) current.activities.add(target);
                    }
                } else if ("version".equals(name) && current == null) {
                    result.version = PickupEvent.clean(parser.nextText());
                } else if (current == null && "webviewname".equals(name)) {
                    var names = io.github.mio.autopickupisland.coloros.ColorOsWebViewNames.read(parser);
                    // m6.h.c replaces the prior entry for the exact package; it
                    // does not merge classes across repeated XML blocks.
                    if (names != null) policy.webViewNames.put(names.packageName(), names);
                } else if (current == null && isPrepareTag(name)) {
                    ColorOsRecognitionPolicy.Host host = switch (name) {
                        case "order_prepare_status_wx" -> ColorOsRecognitionPolicy.Host.WECHAT;
                        case "order_prepare_status_ali" -> ColorOsRecognitionPolicy.Host.ALIPAY;
                        default -> ColorOsRecognitionPolicy.Host.NATIVE;
                    };
                    policy.preparing.add(new ColorOsRecognitionPolicy.PrepareStatus(host,
                            attr(parser, "app_name"), attr(parser, "detail_activity"), attr(parser, "process_status")));
                } else if (current == null && isPolicyTag(name)) {
                    policy.read(name, parser.nextText().trim());
                } else if (current != null && isSimpleRuleTag(name)) {
                    String value = parser.nextText();
                    assign(current, name, "black_paths".equals(name) ? value.trim() : PickupEvent.clean(value));
                }
            } else if (type == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("filter_paths".equals(name) || "monitor_activities".equals(name)) {
                    section = "";
                } else if ("scan".equals(name) && current != null) {
                    if (!current.packageName.isEmpty()) result.rules.add(current);
                    current = null;
                }
            }
        }
        result.policy = policy.build();
        return result;
    }

    private static boolean isPrepareTag(String name) {
        return "order_prepare_status".equals(name) || "order_prepare_status_wx".equals(name)
                || "order_prepare_status_ali".equals(name);
    }

    private static boolean isPolicyTag(String name) {
        return switch (name) {
            case "order_status_finish_time_gap", "use_label_path_change", "send_card_when_fuzzy_match",
                    "use_remote_ai_plugin", "use_remote_img", "remote_img_send_card", "use_observer",
                    "black_labels" -> true;
            default -> false;
        };
    }

    private static boolean isSimpleRuleTag(String name) {
        return switch (name) {
            case "app_name", "brand_name", "origin_id", "tag_ai_app_name", "launch_path",
                    "app_package", "app_logo", "stickers", "base_bg_style", "aod_static_image",
                    "pickup_btn_color", "pickup_code_color", "card_bg_color", "card_bg_alpha",
                    "black_paths", "extract_wx_root_portal", "need_waiting_status" -> true;
            default -> false;
        };
    }

    private static void assign(Rule rule, String name, String value) {
        switch (name) {
            case "app_name" -> rule.appName = value;
            case "brand_name" -> rule.brandName = value;
            case "origin_id" -> rule.originId = value;
            case "tag_ai_app_name" -> rule.tagAppName = value;
            case "launch_path" -> rule.launchPath = value;
            case "app_package" -> rule.appPackage = value;
            case "app_logo" -> rule.logo = value;
            case "stickers" -> rule.sticker = value;
            case "base_bg_style" -> rule.baseStyle = value;
            case "aod_static_image" -> rule.aodImage = value;
            case "pickup_btn_color" -> rule.pickupButtonColor =
                    PickupEvent.color(value, "#3482FF");
            case "pickup_code_color" -> rule.pickupColor = PickupEvent.color(value, "#FFFFFF");
            case "card_bg_color" -> rule.cardColor = PickupEvent.color(value, "#3A3A3A");
            case "card_bg_alpha" -> {
                try {
                    rule.cardAlpha = PickupEvent.alpha(Double.parseDouble(value), 0.12f);
                } catch (RuntimeException ignored) {
                    rule.cardAlpha = 0.12f;
                }
            }
            case "black_paths" -> rule.blackPaths = value;
            case "extract_wx_root_portal" -> rule.extractRootPortal = Boolean.parseBoolean(value);
            case "need_waiting_status" -> rule.needWaitingStatus = Boolean.parseBoolean(value);
            default -> {
            }
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    static boolean containsSameRule(List<Rule> rules, Rule candidate) {
        for (Rule rule : rules) {
            if (rule.packageName.equals(candidate.packageName)
                    && rule.originId.equals(candidate.originId)
                    && rule.webView == candidate.webView
                    && rule.needWaitingStatus == candidate.needWaitingStatus
                    && rule.category.equals(candidate.category)
                    && rule.blackPaths.equals(candidate.blackPaths)
                    && sameTargets(rule.activities, candidate.activities)
                    && sameTargets(rule.filterPaths, candidate.filterPaths)) return true;
        }
        return false;
    }

    private static boolean sameTargets(List<Rule.Target> first, List<Rule.Target> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            Rule.Target a = first.get(index), b = second.get(index);
            if (!a.value.equals(b.value) || a.useCloud != b.useCloud
                    || a.ignoreVisibility != b.ignoreVisibility) return false;
        }
        return true;
    }

    private static final class ParseResult {
        final ArrayList<Rule> rules = new ArrayList<>();
        String version = "";
        ColorOsRecognitionPolicy policy;
    }
}
