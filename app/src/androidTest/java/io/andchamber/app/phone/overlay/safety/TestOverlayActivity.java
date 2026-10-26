package io.andchamber.app.phone.overlay.safety;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Test-only owner of one real TYPE_APPLICATION_OVERLAY window.
 *
 * <p>This class is Java on purpose: the androidTest APK is launched as a normal app process for
 * this probe, so it must not depend on Kotlin stdlib classes that Gradle keeps only in the target
 * APK. It is the independent overlay probe package, not production UI.</p>
 *
 * <p>The instrumentation test grants SYSTEM_ALERT_WINDOW to this package only for the P0. That
 * grant is test preparation, not evidence of visibility: the test reads the real
 * {@code dumpsys window} state for this window token.</p>
 */
public final class TestOverlayActivity extends Activity {
    private static final String TAG = "TestOverlayP0";

    public static final String ACTION_CONTROL =
            "io.andchamber.app.phone.overlay.safety.CONTROL";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_X = "x";
    public static final String EXTRA_Y = "y";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String COMMAND_SHOW = "show";
    public static final String COMMAND_HIDE = "hide";
    public static final String COMMAND_MOVE = "move";
    public static final String COMMAND_EXPAND = "expand";
    public static final String COMMAND_STOP = "stop";

    public static final int DEFAULT_X = 100;
    public static final int DEFAULT_Y = 400;
    public static final int DEFAULT_WIDTH = 160;
    public static final int DEFAULT_HEIGHT = 160;
    public static final int MARKER_COLOR = Color.MAGENTA;

    /**
     * Stable, manager-style window key. The production manager uses its own
     * `andchamber-phone-overlay:<owner>:<generation>`; the driver matches this title in the real
     * `dumpsys window` name and keeps the binder token on its own side.
     */
    public static final String WINDOW_TITLE = "andchamber-phone-overlay:p0:1";

    /** Diagnostics only; the real gate is the window-manager state read by the P0. */
    public static volatile String lastError;
    public static volatile boolean canDrawOverlaysAtStart;

    private WindowManager overlayWindowManager;
    private View view;
    private WindowManager.LayoutParams params;
    private boolean receiverRegistered;
    // Geometry survives a manager hide/restore, exactly like a real overlay would: hiding removes
    // the window from the tree, it must not reset the capsule position.
    private int savedX = DEFAULT_X;
    private int savedY = DEFAULT_Y;
    private int savedWidth = DEFAULT_WIDTH;
    private int savedHeight = DEFAULT_HEIGHT;

    private final BroadcastReceiver control = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_CONTROL.equals(intent.getAction())) {
                return;
            }
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (COMMAND_HIDE.equals(command)) {
                removeOverlay();
            } else if (COMMAND_SHOW.equals(command)) {
                showOverlay();
            } else if (COMMAND_MOVE.equals(command)) {
                savedX = intent.getIntExtra(EXTRA_X, savedX);
                savedY = intent.getIntExtra(EXTRA_Y, savedY);
                if (params != null) {
                    params.x = savedX;
                    params.y = savedY;
                    if (view != null) {
                        overlayWindowManager.updateViewLayout(view, params);
                    }
                }
            } else if (COMMAND_EXPAND.equals(command)) {
                savedWidth = intent.getIntExtra(EXTRA_WIDTH, savedWidth);
                savedHeight = intent.getIntExtra(EXTRA_HEIGHT, savedHeight);
                if (params != null) {
                    params.width = savedWidth;
                    params.height = savedHeight;
                    if (view != null) {
                        overlayWindowManager.updateViewLayout(view, params);
                    }
                }
            } else if (COMMAND_STOP.equals(command)) {
                removeOverlay();
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overlayWindowManager = getSystemService(WindowManager.class);
        lastError = null;
        canDrawOverlaysAtStart = Settings.canDrawOverlays(this);
        Log.i(TAG, "onCreate canDrawOverlays=" + canDrawOverlaysAtStart
                + " opPackage=" + getOpPackageName());
        registerControlReceiver();
        showOverlay();
        // The overlay belongs to this process, not to this Activity window; move the Activity task
        // away so the selected fixture remains the foreground target.
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        removeOverlay();
        if (receiverRegistered) {
            try {
                unregisterReceiver(control);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void registerControlReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_CONTROL);
        androidx.core.content.ContextCompat.registerReceiver(
                this, control, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    private void showOverlay() {
        if (view != null) {
            return;
        }
        TextView marker = new TextView(this);
        marker.setText("OVERLAY");
        marker.setTextColor(Color.WHITE);
        marker.setBackgroundColor(Color.MAGENTA);
        marker.setGravity(Gravity.CENTER);
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                savedWidth,
                savedHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START;
        layout.x = savedX;
        layout.y = savedY;
        layout.setTitle(WINDOW_TITLE);
        try {
            overlayWindowManager.addView(marker, layout);
        } catch (RuntimeException failure) {
            lastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Log.e(TAG, "addView refused", failure);
            return;
        }
        view = marker;
        params = layout;
        Log.i(TAG, "overlay added token=" + (marker.getWindowToken() == null
                ? "null" : marker.getWindowToken().toString()));
    }

    private void removeOverlay() {
        if (view != null) {
            try {
                overlayWindowManager.removeView(view);
            } catch (RuntimeException ignored) {
                // The window token may already be gone with the process.
            }
        }
        view = null;
        params = null;
    }
}
