package com.carol.control;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Home-screen battery. Reads the last levels saved on the phone. No network. */
public class BattWidget extends AppWidgetProvider {
    static void push(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            ComponentName me = new ComponentName(ctx, BattWidget.class);
            int[] ids = mgr.getAppWidgetIds(me);
            for (int id : ids) bind(ctx, mgr, id);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) bind(ctx, mgr, id);
    }

    private static void bind(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_batt);
        SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
        int l = p.getInt("batt_l", -1);
        int r = p.getInt("batt_r", -1);
        int c = p.getInt("batt_c", -1);
        boolean live = p.getBoolean("batt_live", false);
        String text = side("L", l) + "   " + side("R", r);
        if (c >= 1 && c <= 100) text += "   case " + c + "%";
        if (!live && (l >= 0 || r >= 0)) text = "Last  " + text;
        v.setTextViewText(R.id.w_batt, text);
        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(ctx, 8, open, Compat.piFlags()));
        mgr.updateAppWidget(id, v);
    }

    private static String side(String name, int n) {
        return n >= 0 && n <= 100 ? name + " " + n + "%" : name + " —";
    }
}
