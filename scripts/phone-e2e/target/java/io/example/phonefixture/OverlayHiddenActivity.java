package io.example.phonefixture;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test-only page that asks Android to hide non-system overlays from this app's window.
 *
 * <p>This is the positive target for the AndChamber overlay safety P0: when the platform really
 * hides the overlay, the App-side witness must become unavailable and agent actions/capture must be
 * refused. It is not part of the model fixture and is not a production UI.</p>
 */
public final class OverlayHiddenActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setId(R.id.fixture_overlay_hidden_title);
        title.setText(R.string.fixture_overlay_hidden_title);
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        TextView body = new TextView(this);
        body.setId(R.id.fixture_overlay_hidden_body);
        body.setText(R.string.fixture_overlay_hidden_body);
        body.setTextSize(16);
        body.setPadding(0, 0, 0, dp(8));
        root.addView(body, matchWrap());
        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getWindow().setHideOverlayWindows(true);
            } catch (SecurityException unavailable) {
                body.setText("Hide overlay windows is not permitted on this build.");
            }
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
