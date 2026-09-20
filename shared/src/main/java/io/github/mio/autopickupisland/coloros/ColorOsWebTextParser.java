package io.github.mio.autopickupisland.coloros;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * ColorOS ExSystem i6.l.o/p (resultType 4), including ordered multi-WebView merge.
 * This is the collection protocol, NOT the PCR recognizer or the final page gate.
 * Exact strings, JSONObject.NULL's "null", duplicate text, and the previous route
 * are intentional. Do not flatten WebViews and run a heuristic over their union.
 */
public final class ColorOsWebTextParser {
    public interface PathPolicy {
        List<String> filterPaths(); // Original m6.k.l(), not the black-path-filtered subset.
        boolean useCloud(String path); // Original m6.k.w().
    }

    public static final class State {
        // Original i6.j defaults / h,l,i,m getters respectively.
        public String route = "";
        public String href;
        public String query = "";
        public String wechatAppId = "";
        private final ArrayList<String> texts = new ArrayList<>();
        public List<String> texts() { return List.copyOf(texts); }
    }

    public record Result(State state, boolean malformed, boolean limitExceeded) { }

    private static final int MAX_RESULT_CHARS = 1_048_576;
    private static final int MAX_WEBVIEW_BATCH = 64;

    private ColorOsWebTextParser() { }

    public static Result parse(String raw, String previousRoute, PathPolicy policy) {
        State state = new State();
        state.route = previousRoute;
        if (raw == null || raw.isEmpty() || "null".equals(raw))
            return new Result(state, false, false); // k6.c.i.
        // Host safety additions, kept outside the original per-row decisions.
        if (raw.length() > MAX_RESULT_CHARS) return new Result(state, false, true);
        try {
            JSONArray outer = new JSONArray(raw);
            if (outer.length() > 0 && outer.getString(0).startsWith("[")) {
                if (outer.length() > MAX_WEBVIEW_BATCH) return new Result(state, false, true);
                for (int i = 0; i < outer.length(); i++) {
                    parseRow(new JSONArray(outer.getString(i)), state, policy);
                }
            } else {
                parseRow(outer, state, policy);
            }
            return new Result(state, false, false);
        } catch (JSONException e) {
            // i6.l.o catches around the whole batch: keep rows already accepted,
            // stop here, and never continue after a malformed middle row.
            return new Result(state, true, false);
        }
    }

    private static void parseRow(JSONArray row, State state, PathPolicy policy) throws JSONException {
        if (row == null || row.length() < 5 || policy == null) return;
        String href = row.getString(0);
        String text = row.getString(1);
        String route = row.getString(2);
        String wechatAppId = row.getString(3);
        // Original ignores index 4 here and only reads query when length == 6.
        String query = row.length() == 6 ? row.getString(5) : "null";
        if (text.isEmpty()) return;
        List<String> paths = policy.filterPaths();
        if (paths == null || paths.isEmpty()) return; // Host guard for a broken policy adapter.
        if (state.route != null && !state.route.isEmpty()) {
            if (paths.contains(state.route)) {
                if (!paths.contains(route)) return;
                if (policy.useCloud(state.route) && !policy.useCloud(route)) return;
            } else if (route.isEmpty()) {
                return;
            }
        }
        state.href = href;
        state.route = route;
        state.query = query;
        state.wechatAppId = wechatAppId;
        state.texts.add(text);
    }
}
