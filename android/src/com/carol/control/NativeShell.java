package com.carol.control;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small native home, for comparison. The WebView app is unchanged behind it. */
final class NativeShell extends ScrollView {
    interface Host {
        void connect();
        void anc(String mode);
        void spatial(String mode);
        void motion(boolean on);
        void openFull();
        String statusText();
        String batteryText();
    }

    private final TextView status;
    private final TextView battery;
    private final Host host;

    NativeShell(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setFillViewport(true);
        setBackgroundColor(0xFF0B0B0C);
        float d = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, (int) (28 * d));

        TextView kicker = label(ctx, "NATIVE TEST", 12, 0xFFC9A227);
        TextView title = label(ctx, "KZ", 34, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        status = label(ctx, "Not connected", 16, 0xFFB7B7B7);
        battery = label(ctx, "Battery —", 20, 0xFFFFFFFF);
        TextView note = label(ctx, "Same buds, same commands. This screen is plain Android views. The full app is still there.", 14, 0xFF8E8E8E);

        col.addView(kicker);
        col.addView(title);
        col.addView(status);
        col.addView(gap(ctx, 8));
        col.addView(battery);
        col.addView(gap(ctx, 8));
        col.addView(note);
        col.addView(gap(ctx, 18));
        col.addView(button(ctx, "Connect paired", v -> host.connect()));
        col.addView(section(ctx, "Noise"));
        col.addView(row(ctx,
                button(ctx, "Standard", v -> host.anc("off")),
                button(ctx, "Reduction", v -> host.anc("anc")),
                button(ctx, "Transparency", v -> host.anc("transparency"))));
        col.addView(section(ctx, "Sound"));
        col.addView(row(ctx,
                button(ctx, "Stereo", v -> host.spatial("off")),
                button(ctx, "Wide", v -> host.spatial("71")),
                button(ctx, "360", v -> host.spatial("360"))));
        col.addView(section(ctx, "On this phone"));
        col.addView(row(ctx,
                button(ctx, "Motion ANC on", v -> host.motion(true)),
                button(ctx, "Motion ANC off", v -> host.motion(false))));
        col.addView(gap(ctx, 18));
        col.addView(button(ctx, "Open the full app", v -> host.openFull()));
        addView(col, new ScrollView.LayoutParams(-1, -2));
    }

    void refresh() {
        status.setText(host.statusText());
        battery.setText(host.batteryText());
    }

    private TextView label(Context ctx, String text, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, 4, 0, 4);
        return t;
    }

    private TextView section(Context ctx, String text) {
        TextView t = label(ctx, text.toUpperCase(), 12, 0xFFC9A227);
        t.setPadding(0, 22, 0, 8);
        return t;
    }

    private LinearLayout gap(Context ctx, int dp) {
        LinearLayout g = new LinearLayout(ctx);
        g.setMinimumHeight((int) (dp * ctx.getResources().getDisplayMetrics().density));
        return g;
    }

    private LinearLayout row(Context ctx, Button... buttons) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = (int) (8 * ctx.getResources().getDisplayMetrics().density);
            row.addView(b, lp);
        }
        return row;
    }

    private Button button(Context ctx, String text, View.OnClickListener click) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFF0B0B0C);
        b.setTextSize(15);
        b.setMinHeight((int) (48 * ctx.getResources().getDisplayMetrics().density));
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFEDEAE3);
        bg.setCornerRadius(14 * ctx.getResources().getDisplayMetrics().density);
        b.setBackground(bg);
        b.setOnClickListener(click);
        return b;
    }
}
