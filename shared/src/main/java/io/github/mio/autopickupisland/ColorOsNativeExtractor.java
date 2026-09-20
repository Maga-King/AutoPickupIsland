package io.github.mio.autopickupisland;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** i6.b.h/i/c -> g6.f.B native text branch, with bounded OS4 transport adaptation.
 * No OCR, description/HTML substitution, global distinct, or app-process hooks.
 * Password/assist-blocked subtrees are intentionally stricter than the OEM code.
 */
public final class ColorOsNativeExtractor {
    public record Result(String text, String status, int visited, int fallbackVisibility) { }
    /** Minimal OEM-selector input; lets a source process avoid restricted
     * AssistStructure constructors without maintaining a second selector. */
    public interface Node {
        boolean isAssistBlocked();
        int getInputType();
        int getVisibility();
        float getAlpha();
        Bundle getExtras();
        String getClassName();
        CharSequence getText();
        CharSequence getContentDescription();
        int getWidth();
        int getHeight();
        int getChildCount();
        Node getChildAt(int index);
    }
    private record AssistNode(AssistStructure.ViewNode value) implements Node {
        public boolean isAssistBlocked() { return value.isAssistBlocked(); }
        public int getInputType() { return value.getInputType(); }
        public int getVisibility() { return value.getVisibility(); }
        public float getAlpha() { return value.getAlpha(); }
        public Bundle getExtras() { return value.getExtras(); }
        public String getClassName() { return value.getClassName(); }
        public CharSequence getText() { return value.getText(); }
        public CharSequence getContentDescription() { return value.getContentDescription(); }
        public int getWidth() { return value.getWidth(); }
        public int getHeight() { return value.getHeight(); }
        public int getChildCount() { return value.getChildCount(); }
        public Node getChildAt(int index) {
            var child = value.getChildAt(index); return child == null ? null : new AssistNode(child);
        }
    }
    private record Pending(Node node, boolean ancestorsVisible) { }
    private static final int MAX_WINDOWS = 8, MAX_NODES = 6000, MAX_CHILDREN = 500;
    private ColorOsNativeExtractor() { }

    static Result extract(AssistStructure structure, ComponentName expected, boolean ignoreVisibility) {
        try {
            if (structure == null || expected == null
                    || !expected.equals(structure.getActivityComponent()))
                return new Result("", "ACTIVITY_MISMATCH", 0, 0);
            int count = structure.getWindowNodeCount();
            if (count < 1 || count > MAX_WINDOWS) return new Result("", "WINDOW_LIMIT", 0, 0);
            List<AssistStructure.ViewNode> roots = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var window = structure.getWindowNodeAt(i);
                if (window == null || window.getRootViewNode() == null)
                    return new Result("", "INVALID_WINDOW", 0, 0);
                roots.add(window.getRootViewNode());
            }
            return extractRoots(roots, ignoreVisibility);
        } catch (Throwable ignored) { return new Result("", "INVALID_STRUCTURE", 0, 0); }
    }

    // Shared with the source-process fixture; same production selector, not a test copy.
    public static Result extractRoots(List<AssistStructure.ViewNode> roots, boolean ignoreVisibility) {
        if (roots == null || roots.size() > MAX_WINDOWS) return new Result("", "WINDOW_LIMIT", 0, 0);
        List<Node> adapted = new ArrayList<>();
        for (var root : roots) adapted.add(root == null ? null : new AssistNode(root));
        return extractNodes(adapted, ignoreVisibility);
    }

    public static Result extractNodes(List<? extends Node> roots, boolean ignoreVisibility) {
        int visited = 0, fallback = 0, characters = 0;
        List<String> selected = new ArrayList<>();
        StringBuilder output = new StringBuilder();
        try {
            if (roots == null || roots.size() > MAX_WINDOWS)
                return new Result("", "WINDOW_LIMIT", 0, 0);
            for (var root : roots) {
                ArrayDeque<Pending> stack = new ArrayDeque<>();
                if (root == null) return new Result("", "INVALID_WINDOW", visited, fallback);
                stack.push(new Pending(root, true));
                while (!stack.isEmpty()) {
                    if (++visited > MAX_NODES) return new Result("", "NODE_LIMIT", visited, fallback);
                    Pending pending = stack.pop();
                    var node = pending.node;
                    // ignoreVis never overrides privacy exclusions.
                    if (node.isAssistBlocked() || password(node.getInputType())) continue;
                    boolean visible = pending.ancestorsVisible && node.getVisibility() == View.VISIBLE
                            && node.getAlpha() > 0f;
                    Bundle extras = node.getExtras();
                    String extraClass = extras == null ? null : extras.getString("extraClassName");
                    boolean selectedVisibility = visible;
                    if (!ignoreVisibility) {
                        if (extras != null && extras.containsKey("isVisibleToUserIgnoreRoot"))
                            selectedVisibility = extras.getBoolean("isVisibleToUserIgnoreRoot", false);
                        else fallback++; // OS4 snapshot lacks OEM live-View metadata; conservative fallback.
                    }
                    String type = node.getClassName();
                    CharSequence text = node.getText(), description = node.getContentDescription();
                    boolean emptyText = text == null || text.length() == 0;
                    boolean emptyDescription = description == null || description.length() == 0;
                    boolean emptyPlain = ("android.view.View".equals(type) || "android.widget.TextView".equals(type))
                            && type.equals(extraClass) && emptyText && emptyDescription;
                    boolean emptyContainer = ("android.widget.FrameLayout".equals(type)
                            || "android.widget.LinearLayout".equals(type)
                            || "android.widget.RelativeLayout".equals(type)
                            || "android.view.ViewGroup".equals(type)) && emptyText && emptyDescription;
                    if ((ignoreVisibility || selectedVisibility) && !emptyPlain && !emptyContainer
                            && node.getWidth() > 0 && node.getHeight() > 0 && !emptyText) {
                        String value = text.toString(); // j6.a carries getText ONLY, not contentDescription.
                        characters += value.length() + 1;
                        if (characters > 40_000)
                            return new Result("", "TEXT_LIMIT", visited, fallback);
                        selected.add(value);
                    }
                    int children = node.getChildCount();
                    if (children > MAX_CHILDREN) return new Result("", "CHILD_LIMIT", visited, fallback);
                    // Original h traverses children even when this node was not selected.
                    for (int i = children - 1; i >= 0; i--) {
                        var child = node.getChildAt(i);
                        if (child == null) return new Result("", "INVALID_CHILD", visited, fallback);
                        stack.push(new Pending(child, visible));
                    }
                }
                // i6.b.i iterates the accumulated NodeData list after EACH window.
                // Preserve this peculiar multi-window repetition; do not sort/distinct.
                for (String value : selected) {
                    if (output.length() + value.length() + 1 > 40_000)
                        return new Result("", "TEXT_LIMIT", visited, fallback);
                    if (output.length() != 0) output.append('\n');
                    output.append(value);
                }
            }
            return new Result(output.toString(), output.length() == 0 ? "EMPTY" : "OK", visited, fallback);
        } catch (Throwable ignored) { return new Result("", "INVALID_NODE", visited, fallback); }
    }

    private static boolean password(int input) {
        int type = input & InputType.TYPE_MASK_CLASS, variation = input & InputType.TYPE_MASK_VARIATION;
        return type == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }
}
