package com.carol.control;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Locale;

/**
 * Re-applies the last spatial/EQ profile when Carol powers on and joins A2DP.
 */
public class BtWatch extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (a == null) return;
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
            int radio = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (radio == BluetoothAdapter.STATE_OFF || radio == BluetoothAdapter.STATE_TURNING_OFF) {
                MainActivity.bluetoothOff();
            }
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)) {
            return;
        }
        BluetoothDevice d = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
        } catch (Exception ignored) {
        }
        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
        boolean on = BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)
                || state == BluetoothProfile.STATE_CONNECTED;
        boolean off = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)
                || state == BluetoothProfile.STATE_DISCONNECTED;
        if (on && isCarol(ctx, d)) {
            CarolService.buds(ctx, true);
        } else if (off && isCarol(ctx, d)) {
            LastLost.radioDrop(ctx, d);
            LastLost.capture(ctx);
            CarolService.buds(ctx, false);
            if (!MainActivity.uiResumed) {
                try { CarolService.stop(ctx); } catch (Throwable ignored) {}
            }
        }
    }

    static boolean isCarol(Context ctx, BluetoothDevice d) {
        SharedPreferences p = ctx.getSharedPreferences("carol_ble", 0);
        String saved = p.getString("addr", "");
        if (d == null) return saved != null && saved.length() > 0;
        String addr = d.getAddress();
        if (addr != null && addr.equalsIgnoreCase(saved)) return true;
        String name = "";
        try {
            name = d.getName() == null ? "" : d.getName();
        } catch (SecurityException ignored) {
        }
        String n = name.toLowerCase(Locale.US);
        return KzCatalog.likely(name) || n.contains("kz");
    }
}
