package io.github.mio.autopickupisland.coloros;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import io.github.mio.autopickupisland.ColorOsNativeExtractor;
import java.util.ArrayList;
import java.util.List;

/** One-node sink for the platform's PUBLIC dispatchProvideStructure API.
 * The sentinel child count stops ViewGroup's automatic subtree traversal AFTER
 * View's own assist-blocked gate. The collector performs its own bounded,
 * ordered traversal. Custom dispatch/virtual providers are rejected beforehand.
 * Never passed across Binder or retained in the returned immutable node tree.
 */
final class NativeNodeWriter extends ViewStructure {
    boolean blocked;
    String type = "", text = "", description = "";
    int width, height, input, visibility = View.VISIBLE;
    float alpha = 1f;
    private AutofillId id;
    private final Bundle extras = new Bundle();
    Bundle metadata = new Bundle();
    final ArrayList<NativeNodeWriter> children = new ArrayList<>();

    ColorOsNativeExtractor.Node freeze() {
        ArrayList<ColorOsNativeExtractor.Node> frozen = new ArrayList<>();
        for (NativeNodeWriter child : children) frozen.add(child.freeze());
        return new Frozen(blocked, type, text, description, width, height, input, visibility, alpha,
                new Bundle(metadata), List.copyOf(frozen));
    }
    private record Frozen(boolean blocked, String type, String text, String description,
                          int width, int height, int input, int visibility, float alpha,
                          Bundle metadata, List<ColorOsNativeExtractor.Node> children) implements ColorOsNativeExtractor.Node {
        public boolean isAssistBlocked() { return blocked; }
        public int getInputType() { return input; }
        public int getVisibility() { return visibility; }
        public float getAlpha() { return alpha; }
        public Bundle getExtras() { return new Bundle(metadata); }
        public String getClassName() { return type; }
        public CharSequence getText() { return text; }
        public CharSequence getContentDescription() { return description; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public int getChildCount() { return children.size(); }
        public ColorOsNativeExtractor.Node getChildAt(int index) { return children.get(index); }
    }
    private static String copy(CharSequence value) {
        if (value == null) return "";
        if (value.length() > 40_000) throw new IllegalArgumentException("TextLimit");
        return value.toString();
    }
    // These two abstract framework callbacks are hidden from android.jar, but
    // implemented normally as virtual overrides. No reflective lookup/invocation.
    public void setAssistBlocked(boolean value) { blocked = value; }
    public Rect getTempRect() { return new Rect(); }
    @Override public int getChildCount() { return 1; }
    @Override public void setChildCount(int count) { throw new UnsupportedOperationException("VirtualChildren"); }
    @Override public int addChildCount(int count) { throw new UnsupportedOperationException("VirtualChildren"); }
    @Override public ViewStructure newChild(int index) { throw new UnsupportedOperationException("VirtualChildren"); }
    @Override public ViewStructure asyncNewChild(int index) { throw new UnsupportedOperationException("AsyncChildren"); }
    @Override public void asyncCommit() { throw new UnsupportedOperationException("AsyncChildren"); }
    @Override public void setClassName(String value) { type = copy(value); }
    @Override public void setText(CharSequence value) { text = copy(value); }
    @Override public void setText(CharSequence value, int start, int end) { setText(value); }
    @Override public CharSequence getText() { return text; }
    @Override public int getTextSelectionStart() { return -1; }
    @Override public int getTextSelectionEnd() { return -1; }
    @Override public void setContentDescription(CharSequence value) { description = copy(value); }
    @Override public void setDimens(int left, int top, int sx, int sy, int w, int h) { width = w; height = h; }
    @Override public void setAlpha(float value) { alpha = value; }
    @Override public void setVisibility(int value) { visibility = value; }
    @Override public void setInputType(int value) { input = value; }
    @Override public Bundle getExtras() { return extras; }
    @Override public boolean hasExtras() { return !extras.isEmpty(); }
    @Override public void setAutofillId(AutofillId value) { id = value; }
    @Override public void setAutofillId(AutofillId parent, int virtualId) { throw new UnsupportedOperationException("VirtualId"); }
    @Override public AutofillId getAutofillId() { return id; }
    @Override public CharSequence getHint() { return null; }
    @Override public void setHint(CharSequence value) { } // Never substitute hint into text.
    @Override public HtmlInfo.Builder newHtmlInfoBuilder(String tag) { return null; }
    @Override public void setHtmlInfo(HtmlInfo value) { }
    @Override public void setAccessibilityFocused(boolean value) { }
    @Override public void setActivated(boolean value) { }
    @Override public void setAutofillHints(String[] value) { }
    @Override public void setAutofillOptions(CharSequence[] value) { }
    @Override public void setAutofillType(int value) { }
    @Override public void setAutofillValue(AutofillValue value) { }
    @Override public void setCheckable(boolean value) { }
    @Override public void setChecked(boolean value) { }
    @Override public void setClickable(boolean value) { }
    @Override public void setContextClickable(boolean value) { }
    @Override public void setDataIsSensitive(boolean value) { }
    @Override public void setElevation(float value) { }
    @Override public void setEnabled(boolean value) { }
    @Override public void setFocusable(boolean value) { }
    @Override public void setFocused(boolean value) { }
    @Override public void setId(int value, String pkg, String kind, String entry) { }
    @Override public void setLocaleList(LocaleList value) { }
    @Override public void setLongClickable(boolean value) { }
    @Override public void setOpaque(boolean value) { }
    @Override public void setSelected(boolean value) { }
    @Override public void setTextLines(int[] offsets, int[] baselines) { }
    @Override public void setTextStyle(float size, int foreground, int background, int style) { }
    @Override public void setTransformation(Matrix value) { }
    @Override public void setWebDomain(String value) { }
}
