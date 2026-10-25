package io.example.phonefixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Second page in the same test package. The system back key finishes it; the visible Back button
 * does the same so a real UI XML check can also tap it.
 */
public final class SecondActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setId(R.id.fixture_second_title);
        title.setText(R.string.fixture_second_title);
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        TextView body = new TextView(this);
        body.setId(R.id.fixture_second_body);
        body.setText(R.string.fixture_second_body);
        body.setTextSize(16);
        body.setPadding(0, 0, 0, dp(8));
        root.addView(body, matchWrap());

        Button back = new Button(this);
        back.setId(R.id.fixture_back);
        back.setText(R.string.fixture_back);
        back.setContentDescription("fixture-back");
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                finish();
            }
        });
        root.addView(back, matchWrap());

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
