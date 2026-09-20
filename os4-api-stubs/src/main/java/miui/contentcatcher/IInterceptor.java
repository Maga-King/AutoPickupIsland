package miui.contentcatcher;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import miui.security.IUIAgentCallback;
public interface IInterceptor {
    boolean dispatchKeyEvent(KeyEvent event, View view, Activity activity);
    boolean dispatchTouchEvent(MotionEvent event, View view, Activity activity);
    void notifyActivityCreate();
    void notifyActivityDestroy();
    void notifyActivityPause();
    void notifyActivityResume();
    void notifyActivityStart();
    void notifyActivityStop();
    void notifyWebView(View view, boolean value);
    void processRequest(Uri uri);
    default void onUiAgent(Bundle params, IUIAgentCallback callback) { }
}
