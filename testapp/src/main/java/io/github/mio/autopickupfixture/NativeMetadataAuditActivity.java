package io.github.mio.autopickupfixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Synthetic-only, outside all pickup rule activities. Never publishes orders. */
public final class NativeMetadataAuditActivity extends Activity {
    FrameLayout root, parent;
    TextView code;
    ImageView image;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root = new FrameLayout(this);
        root.setPadding(32, 48, 32, 32);
        parent = new FrameLayout(this);
        root.addView(parent, new FrameLayout.LayoutParams(600, 400));
        code = new TextView(this);
        code.setText("Native metadata audit — synthetic views only");
        parent.addView(code, new FrameLayout.LayoutParams(450, 90));
        image = new ImageView(this);
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(80, 80);
        imageParams.topMargin = 120;
        parent.addView(image, imageParams);
        setContentView(root);
    }
}
