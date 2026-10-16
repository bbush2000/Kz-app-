package com.carol.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Dismissible status while buds are connected. Not the mix foreground notification. */
final class StatusNote {
    static final int ID = 32;
    static final String CH = "carol_status";
    static final String DISMISS = "com.carol.control.STATUS_DISMISS";
    private static String anc = "";
    private static boolean up;
    private static int left = -1, right = -1, cse = -1;

    private StatusNote() {}

    static void connected(Context ctx) {
        up = true;
        try {
            ctx.getSharedPreferences("carol_ble", 0).edit().putBoolean("status_hide", false).apply();
        } catch (Throwable ignored) {
        }
        show(ctx);
    }

    static void anc(Context ctx, String mode) {
        anc = mode == null ? "" : mode;
        show(ctx);
    }

    static void batt(Context ctx, int l, int r, int c) {
        left = l;
        right = r;
        cse = c;
        try {
            android.content.SharedPreferences.Editor ed = ctx.getSharedPreferences("carol_ble", 0).edit()
                    .putInt("batt_l", l).putInt("batt_r", r).putInt("batt_c", c)
                    .putBoolean("batt_live", true);
            if (l >= 12 && l <= 100 && r >= 12 && r <= 100 && Math.abs(l - r) >= 25) {
                String side = l > r ? "R" : "L";
                android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
                int n = side.equals(p.getString("batt_gap_side", "")) ? p.getInt("batt_gap_n", 0) + 1 : 1;
                ed.putString("batt_gap_side", side).putInt("batt_gap_n", n);
            } else {
                ed.putInt("batt_gap_n", 0);
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
        try { BattWidget.push(ctx); } catch (Throwable ignored) {}
        show(ctx);
    }

    static void clear(Context ctx) {
        up = false;
        anc = "";
        left = right = cse = -1;
        try {
            ctx.getSharedPreferences("carol_ble", 0).edit().putBoolean("batt_live", false).apply();
        } catch (Throwable ignored) {
        }
        try { BattWidget.push(ctx); } catch (Throwable ignored) {}
        hide(ctx);
    }

    static void dismiss(Context ctx) {
        try {
            ctx.getSharedPreferences("carol_ble", 0).edit().putBoolean("status_hide", true).apply();
        } catch (Throwable ignored) {
        }
        hide(ctx);
    }

    private static void hide(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
        }
    }

    private static void show(Context ctx) {
        try {
            if (ctx.getSharedPreferences("carol_ble", 0).getBoolean("status_hide", false)) return;
        } catch (Throwable ignored) {
            return;
        }
        if (!up && anc.length() == 0 && left < 0 && right < 0) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "KZ buds", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        String name = "KZ";
        try {
            String n = ctx.getSharedPreferences("carol_ble", 0).getString("cache_name", "");
            if (n != null && n.length() > 0) name = n;
        } catch (Throwable ignored) {
        }
        String mode = "anc".equals(anc) ? "ANC" : "transparency".equals(anc) ? "Trans" : "Off";
        StringBuilder batt = new StringBuilder();
        if (left >= 0 && left <= 100) batt.append("L ").append(left).append("%");
        if (right >= 0 && right <= 100) {
            if (batt.length() > 0) batt.append("  ");
            batt.append("R ").append(right).append("%");
        }
        if (cse > 0 && cse <= 100) {
            if (batt.length() > 0) batt.append("  ");
            batt.append("Case ").append(cse).append("%");
        }
        String warn = "";
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            if (p.getInt("batt_gap_n", 0) >= 3) {
                String side = p.getString("batt_gap_side", "");
                if ("L".equals(side) || "R".equals(side)) warn = side + " is lower";
            }
        } catch (Throwable ignored) {
        }
        String text = mode;
        if (batt.length() > 0) text = mode + "  ·  " + batt;
        if (warn.length() > 0) text = text + "  ·  " + warn;
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("carol_open", "device");
        PendingIntent pi = PendingIntent.getActivity(ctx, 8, open, Compat.piFlags());
        PendingIntent del = PendingIntent.getBroadcast(ctx, 9,
                new Intent(DISMISS).setPackage(ctx.getPackageName()), Compat.piFlags());
        PendingIntent ancPi = PendingIntent.getBroadcast(ctx, 10,
                new Intent(CarolActions.CYCLE_ANC).setPackage(ctx.getPackageName()), Compat.piFlags());
        PendingIntent findPi = PendingIntent.getBroadcast(ctx, 11,
                new Intent(CarolActions.FIND_PING).setPackage(ctx.getPackageName()), Compat.piFlags());
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CH);
        else b = new Notification.Builder(ctx);
        b.setContentTitle(name)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentIntent(pi)
                .setDeleteIntent(del)
                .setOngoing(false)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_LOW);
        b.addAction(android.R.drawable.stat_sys_headset, "ANC", ancPi);
        b.addAction(android.R.drawable.stat_sys_headset, "Find", findPi);
        Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, n);
        } catch (Throwable ignored) {
        }
    }
}
