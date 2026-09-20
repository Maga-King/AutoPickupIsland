package io.github.mio.autopickupisland;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.net.Uri;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded, password-aware conversion of an AssistStructure into PCR input text. */
final class AssistStructureExtractor {
    private static final int MAX_WINDOWS = 8;
    private static final int MAX_NODES = 6_000;
    private static final int MAX_CHILDREN_PER_NODE = 500;

    private AssistStructureExtractor() {
    }

    static String extract(AssistStructure structure, AssistContent assistContent,
                          String taskLabel) {
        Lines lines = new Lines();
        // Labels and URLs identify the page; they are not order text. In particular,
        // store IDs, URL numbers and browser-version strings must not enter PCR here.
        if (structure != null) {
            try {
                int windows = Math.min(structure.getWindowNodeCount(), MAX_WINDOWS);
                int visited = 0;
                ArrayDeque<AssistStructure.ViewNode> stack = new ArrayDeque<>();
                for (int index = 0; index < windows; index++) {
                    try {
                        AssistStructure.WindowNode window = structure.getWindowNodeAt(index);
                        if (window != null && window.getRootViewNode() != null) {
                            stack.addLast(window.getRootViewNode());
                        }
                    } catch (Throwable ignored) {
                    }
                }
                while (!stack.isEmpty() && visited++ < MAX_NODES
                        && length(lines) < Constants.MAX_CONTENT_LENGTH) {
                    AssistStructure.ViewNode node = stack.removeLast();
                    if (node == null) continue;
                    try {
                        // A VISIBLE descendant of a hidden ancestor is still hidden.
                        // Prune the entire subtree, including password/assist-blocked content.
                        if (isPassword(node) || node.isAssistBlocked()
                                || node.getVisibility() != View.VISIBLE || node.getAlpha() <= 0f) continue;
                        CharSequence text = node.getText();
                        if (text != null && text.length() > 0) addLine(lines, text);
                        else {
                            CharSequence description = node.getContentDescription();
                            if (description != null && description.length() > 0) addLine(lines, description);
                            else addHtml(lines, node.getHtmlInfo());
                        }
                    } catch (Throwable ignored) {
                        continue;
                    }
                    try {
                        int children = Math.min(node.getChildCount(), MAX_CHILDREN_PER_NODE);
                        for (int child = children - 1; child >= 0; child--) {
                            AssistStructure.ViewNode value = node.getChildAt(child);
                            if (value != null) stack.addLast(value);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        StringBuilder output = new StringBuilder();
        for (String line : lines) {
            if (output.length() > 0) output.append('\n');
            int room = Constants.MAX_CONTENT_LENGTH - output.length();
            if (room <= 0) break;
            output.append(line, 0, Math.min(room, line.length()));
        }
        return output.toString();
    }

    static String webUri(AssistContent content) {
        try {
            Uri uri = content == null ? null : content.getWebUri();
            return uri == null ? "" : PickupEvent.truncate(uri.toString(), 4_096);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static boolean looksLikePickup(String content) {
        String lower = PickupEvent.clean(content).toLowerCase(Locale.ROOT);
        return containsAny(lower, "取餐", "取货码", "取茶", "取咖啡", "取杯",
                "餐号", "餐码", "叫号", "等待取", "凭号码", "pickup");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean isPassword(AssistStructure.ViewNode node) {
        try {
            int inputType = node.getInputType();
            int inputClass = inputType & InputType.TYPE_MASK_CLASS;
            int variation = inputType & InputType.TYPE_MASK_VARIATION;
            return inputClass == InputType.TYPE_CLASS_TEXT
                    && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                    || inputClass == InputType.TYPE_CLASS_NUMBER
                    && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void addHtml(Lines lines, ViewStructure.HtmlInfo info) {
        if (info == null) return;
        try {
            List<Pair<String, String>> attributes = info.getAttributes();
            if (attributes == null) return;
            for (Pair<String, String> attribute : attributes) {
                if (attribute == null) continue;
                String key = PickupEvent.clean(attribute.first).toLowerCase(Locale.ROOT);
                if (key.equals("aria-label") || key.equals("alt")) {
                    addLine(lines, attribute.second);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addLine(Lines lines, CharSequence raw) {
        if (raw == null || length(lines) >= Constants.MAX_CONTENT_LENGTH) return;
        String value = raw.toString()
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("[ \\t]+", " ").trim();
        if (value.isEmpty()) return;
        for (String part : value.split("[\\r\\n]+")) {
            String clean = PickupEvent.clean(part);
            if (!clean.isEmpty()) lines.add(PickupEvent.truncate(clean, 500));
        }
    }

    private static int length(Lines lines) {
        return lines.characters;
    }

    private static final class Lines extends ArrayList<String> {
        int characters;
        @Override public boolean add(String value) {
            if (characters + value.length() + 1 > Constants.MAX_CONTENT_LENGTH) return false;
            characters += value.length() + 1;
            // No global distinct: repeated pickup/status labels separate separate orders.
            return super.add(value);
        }
    }
}
