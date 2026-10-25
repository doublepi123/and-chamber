package io.example.phonefixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Test-only target for the AndChamber phone-control E2E fixture.
 *
 * <p>Every visible control has a stable resource id and a deterministic label. The Save button
 * changes {@code Saved 0 times} to {@code Saved N times}; the list is scrollable; the second
 * activity is in the same package and responds to the system back key.</p>
 *
 * <p>The phone driver clears the label of editable and password nodes as a privacy rule. The
 * ordinary input therefore has a non-editable echo TextView witness with a stable id; it starts at
 * a fixed marker and mirrors only {@code fixture_input}. The password field is never mirrored.</p>
 *
 * <p>No network, storage, permissions or real account is used. The password field exists only to
 * prove that a password EditText is a separate, non-editable target; the E2E scenarios never type
 * into it.</p>
 */
public final class MainActivity extends Activity {
    private static final int ROW_COUNT = 30;
    private static final String INPUT_ECHO_EMPTY = "fixture-input-echo-empty";

    private int savedCount;
    private TextView counter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);

        LinearLayout root = new LinearLayout(this);
        root.setId(R.id.fixture_root);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setId(R.id.fixture_title);
        title.setText(R.string.fixture_title);
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        counter = new TextView(this);
        counter.setId(R.id.fixture_counter);
        counter.setText(R.string.fixture_counter_initial);
        counter.setTextSize(18);
        counter.setPadding(0, 0, 0, dp(8));
        root.addView(counter, matchWrap());

        EditText input = new EditText(this);
        input.setId(R.id.fixture_input);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.fixture_input_hint);
        input.setContentDescription("fixture-input");
        root.addView(input, matchWrap());

        // Privacy witness: the driver clears editable/password labels, so this non-editable node
        // mirrors the actual ordinary-input value. It is never attached to the password field.
        TextView inputEcho = new TextView(this);
        inputEcho.setId(R.id.fixture_input_echo);
        inputEcho.setText(INPUT_ECHO_EMPTY);
        inputEcho.setTextSize(14);
        inputEcho.setPadding(0, dp(4), 0, dp(8));
        root.addView(inputEcho, matchWrap());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                inputEcho.setText(text == null ? "" : text.toString());
            }
        });

        EditText password = new EditText(this);
        password.setId(R.id.fixture_password);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("test-only-password");
        password.setHint(R.string.fixture_password_hint);
        password.setContentDescription("fixture-password");
        root.addView(password, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button save = new Button(this);
        save.setId(R.id.fixture_save);
        save.setText(R.string.fixture_save);
        save.setContentDescription("fixture-save");
        save.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                savedCount += 1;
                counter.setText(getString(R.string.fixture_counter_value, savedCount));
            }
        });
        buttons.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button next = new Button(this);
        next.setId(R.id.fixture_next);
        next.setText(R.string.fixture_next);
        next.setContentDescription("fixture-next");
        next.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                startActivity(new Intent(MainActivity.this, SecondActivity.class));
            }
        });
        buttons.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.fixture_list);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int index = 1; index <= ROW_COUNT; index += 1) {
            TextView row = new TextView(this);
            row.setText(getString(R.string.fixture_row, index));
            row.setTextSize(16);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            row.setContentDescription(getString(R.string.fixture_row, index));
            rows.addView(row, matchWrap());
        }
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

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
