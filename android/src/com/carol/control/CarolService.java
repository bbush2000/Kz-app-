package com.carol.control;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps phone-side EQ + spatial on the mix after the UI is closed.
 */
public class CarolService extends Service {
    static final String CH = "carol_profile";
    static final int NID = 31;
    static volatile boolean handshake;
    private static final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable dropHold = () -> {
        handshake = false;
        if (!session(this) && !linked(this) && !MainActivity.uiResumed) stopSelf();
        else try { startFg(); } catch (Throwable ignored) {}
    };

    /** Keep the process up for one handshake. Stops itself if the chip never answers. */
    static void hold(Context ctx) {
        handshake = true;
        start(ctx);
    }

    static void chipUp(Context ctx) {
        handshake = false;
        timer.removeCallbacksAndMessages(null);
        poke(ctx);
    }

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, CarolService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CarolService.class));
    }

    static boolean hasSavedDevice(Context ctx) {
        return ctx.getSharedPreferences("carol_ble", 0).getString("addr", null) != null;
    }

    /** True only while buds or a USB DAC are actually up and the phone mix is running. */
    static boolean session(Context ctx) {
        boolean device = false;
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            device = p.getBoolean("sess_buds", false) || p.getBoolean("sess_usb", false);
        } catch (Throwable ignored) {
            return false;
        }
        if (!device) return false;
        try {
            return SpatialFx.get(ctx).active();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void buds(Context ctx, boolean on) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            if (p.getBoolean("sess_buds", false) == on) return;
            p.edit().putBoolean("sess_buds", on).apply();
        } catch (Throwable ignored) {
            return;
        }
        poke(ctx);
    }

    static void usb(Context ctx, boolean on) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            if (p.getBoolean("sess_usb", false) == on) return;
            p.edit().putBoolean("sess_usb", on).apply();
        } catch (Throwable ignored) {
            return;
        }
        poke(ctx);
    }

    static boolean linked(Context ctx) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
            return p.getBoolean("sess_buds", false) || p.getBoolean("sess_usb", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Keep the link up after the screen turns off. Do not start this from a cold background process. */
    static void poke(Context ctx) {
        if (!MainActivity.uiResumed) return;
        try {
            if (session(ctx) || linked(ctx) || handshake) start(ctx);
            else stop(ctx);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { startFg(); } catch (Throwable ignored) {}
        try { SpatialFx.get(this).reattach(); } catch (Throwable ignored) {}
        try { MicEq.get(this).applyMute(); } catch (Throwable ignored) {}
        try { UsbDac.get(this).restoreIfNeeded(); } catch (Throwable ignored) {}
        try { MixTile.refresh(this); } catch (Throwable ignored) {}
        try { MotionAnc.start(this); } catch (Throwable ignored) {}
        timer.removeCallbacks(dropHold);
        if (handshake) timer.postDelayed(dropHold, 20000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { MotionAnc.stop(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startFg() {
        SpatialFx fx = SpatialFx.get(this);
        boolean finding = handshake && !session(this);
        boolean mix = session(this);
        String mode = fx.mode();
        String title = finding ? "Connecting KZ" : mix ? "KZ mix on" : "KZ connected";
        String label = finding ? "Finding the chip. Media keeps playing."
                : mix ? label(mode) + (fx.bypass() ? " · EQ bypassed" : " · phone mix")
                : "Buds stay linked while the screen is off.";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "KZ mix", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Compat.piFlags());
        PendingIntent bypass = PendingIntent.getBroadcast(this, 2,
                new Intent(CarolActions.BYPASS).setPackage(getPackageName()), Compat.piFlags());
        PendingIntent stereo = PendingIntent.getBroadcast(this, 3,
                new Intent(CarolActions.STEREO).setPackage(getPackageName()), Compat.piFlags());
        PendingIntent cycle = PendingIntent.getBroadcast(this, 4,
                new Intent(CarolActions.CYCLE_SPATIAL).setPackage(getPackageName()), Compat.piFlags());
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH);
        else b = new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(label)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 16 && !finding) {
            b.setPriority(Notification.PRIORITY_LOW);
            addAction(b, fx.bypass() ? "EQ on" : "Bypass EQ", bypass);
            addAction(b, "Stereo", stereo);
            addAction(b, "Next layout", cycle);
        }
        Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NID, n);
            }
        } catch (Exception e) {
            try { stopSelf(); } catch (Exception ignored) {}
        }
    }

    private void addAction(Notification.Builder b, String title, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Icon ic = Icon.createWithResource(this, android.R.drawable.ic_media_pause);
                b.addAction(new Notification.Action.Builder(ic, title, pi).build());
            } else {
                b.addAction(android.R.drawable.ic_media_pause, title, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    static String label(String mode) {
        if (mode == null) return "Stereo";
        switch (mode) {
            case "51": return "Front + surround layout";
            case "71": return "Side + rear layout";
            case "spatial": return "Wide pinna stage";
            case "atmos": return "Height 7.1.4 layout";
            case "dts": return "Punch surround layout";
            case "thx": return "Cinema spatial";
            case "360": return "360° surround";
            case "cross": return "Crossfeed / width";
            default:
                if (mode.startsWith("c_")) return "Custom layout";
                return "Stereo";
        }
    }
}
