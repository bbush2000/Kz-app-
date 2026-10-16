/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.annotation.SuppressLint
 *  android.app.Activity
 *  android.content.Context
 *  android.content.Intent
 *  android.graphics.Insets
 *  android.net.Uri
 *  android.os.Build$VERSION
 *  android.os.Bundle
 *  android.view.View
 *  android.view.ViewGroup
 *  android.view.ViewGroup$LayoutParams
 *  android.view.ViewParent
 *  android.view.WindowInsets$Type
 *  android.webkit.JavascriptInterface
 *  android.webkit.WebResourceRequest
 *  android.webkit.WebSettings
 *  android.webkit.WebSettings$LayoutAlgorithm
 *  android.webkit.WebView
 *  android.webkit.WebViewClient
 *  android.widget.FrameLayout
 *  android.widget.FrameLayout$LayoutParams
 *  android.widget.TextView
 *  org.json.JSONArray
 */
package com.carol.control;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.carol.control.BleBridge;
import com.carol.control.CarolService;
import com.carol.control.CodecCtrl;
import com.carol.control.Compat;
import com.carol.control.DeviceRoster;
import com.carol.control.FitTest;
import com.carol.control.KzCatalog;
import com.carol.control.LastLost;
import com.carol.control.MicEq;
import com.carol.control.MixTile;
import com.carol.control.SpatialFx;
import com.carol.control.SurroundDemo;
import com.carol.control.UsbDac;
import com.carol.control.UserState;
import com.carol.control.VolumeUnlock;
import java.util.ArrayList;
import org.json.JSONArray;

public class MainActivity
extends Activity {
    static volatile boolean uiResumed;
    static volatile MainActivity liveUi;
    private WebView web;
    private FrameLayout root;
    private NativeShell shell;
    private boolean webLoaded;
    private BleBridge ble;
    private SpatialFx spatial;
    private MicEq micEq;
    private FitTest fit;
    private SurroundDemo heli;
    private boolean scanAfterPerms;
    private boolean fitAfterPerms;
    private boolean listenAfterPerms;
    private boolean locAfterPerms;
    private boolean openDevice;
    private int openTries;

    private void syncSession() {
        if (!uiResumed) {
            return;
        }
        try {
            if (CarolService.session((Context)this)) {
                CarolService.start((Context)this);
            } else {
                CarolService.stop((Context)this);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    @SuppressLint(value={"SetJavaScriptEnabled", "AddJavascriptInterface"})
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try {
            UserState.boot((Context)this);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        FrameLayout frameLayout = new FrameLayout((Context)this);
        frameLayout.setBackgroundColor(-16053492);
        this.root = frameLayout;
        this.setContentView((View)frameLayout);
        if (Build.VERSION.SDK_INT >= 35) {
            frameLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, insets.top, 0, insets.bottom);
                return windowInsets;
            });
        }
        this.lateInit();
        this.showWeb();
        try {
            this.requestLaunchPerms();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            this.handleUsbIntent(this.getIntent());
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        this.noteOpen(this.getIntent());
    }

    private void lateInit() {
        try {
            this.ble = new BleBridge((Context)this);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            this.spatial = SpatialFx.get((Context)this);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            this.micEq = MicEq.get((Context)this);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            UsbDac.get((Context)this);
            UsbDac.get((Context)this).setListener(this::pushUsb);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            this.fit = new FitTest((Context)this, string -> {
                if (this.web != null) {
                    this.web.evaluateJavascript("window.onCarolEvent && window.onCarolEvent(" + string + ")", null);
                }
            });
            this.heli = new SurroundDemo((Context)this, string -> {
                if (this.web != null) {
                    this.web.evaluateJavascript("window.onCarolEvent && window.onCarolEvent(" + string + ")", null);
                }
            });
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (this.ble != null) {
            this.ble.setListener(string -> {
                this.runOnUiThread(() -> {
                    if (this.shell != null && this.shell.getVisibility() == View.VISIBLE) this.shell.refresh();
                    if (this.web != null) {
                        this.web.evaluateJavascript("window.onCarolEvent && window.onCarolEvent(" + string + ")", null);
                    }
                });
            });
        }
        if (this.web != null) {
            this.web.post(() -> {
                try {
                    this.syncSession();
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
            });
        } else {
            try {
                this.syncSession();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    private void pushUsb() {
        if (this.web == null) {
            return;
        }
        String string = UsbDac.get((Context)this).status();
        this.runOnUiThread(() -> this.web.evaluateJavascript("window.onCarolEvent&&window.onCarolEvent({\"type\":\"usb\",\"status\":" + string + "})", null));
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        this.setIntent(intent);
        this.noteOpen(intent);
        this.handleUsbIntent(intent);
        this.pushOpen();
    }

    private void noteOpen(Intent intent) {
        if (intent != null && "device".equals(intent.getStringExtra("carol_open"))) {
            this.openDevice = true;
            this.openTries = 0;
        }
    }

    private void pushOpen() {
        if (!this.openDevice || this.web == null || this.openTries > 8) return;
        this.openTries++;
        this.web.evaluateJavascript("(function(){if(!window.carolOpen)return 0;window.carolOpen('device');return 1})()", string -> {
            if ("1".equals(string)) {
                this.openDevice = false;
                this.openTries = 0;
            } else if (this.openDevice && this.web != null) {
                this.web.postDelayed(this::pushOpen, 400);
            }
        });
    }

    private void handleUsbIntent(Intent intent) {
        if (intent != null && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            UsbDac.get((Context)this).onAttached();
        }
    }

    private void tuneWebView(WebView webView) {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setCacheMode(-1);
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT < 26) {
            webSettings.setSaveFormData(false);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            webSettings.setMixedContentMode(1);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            webSettings.setOffscreenPreRaster(false);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            webSettings.setSafeBrowsingEnabled(false);
        }
        webView.setBackgroundColor(-16053492);
        webView.setOverScrollMode(2);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        Compat.webLayer(webView, (Context)this);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        webView.setInitialScale(100);
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled((boolean)false);
        }
    }

    protected void onResume() {
        super.onResume();
        uiResumed = true;
        liveUi = this;
        if (this.web != null) {
            this.web.onResume();
        }
        if (this.ble != null) {
            this.ble.emitAbsVol();
            this.ble.wake();
        }
        try {
            SpatialFx.get((Context)this).reattach();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            UsbDac.get((Context)this).restoreIfNeeded();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            this.syncSession();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            MixTile.refresh((Context)this);
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (this.web != null) {
            this.web.evaluateJavascript("window.onCarolEvent&&window.onCarolEvent({\"type\":\"mix\",\"spatial\":\"" + SpatialFx.get((Context)this).mode() + "\",\"bypass\":" + (SpatialFx.get((Context)this).bypass() ? "true" : "false") + ",\"tune\":" + SpatialFx.get((Context)this).tuneJson() + "})", null);
            this.web.evaluateJavascript("window.onCarolEvent&&window.onCarolEvent({\"type\":\"usb\",\"status\":" + UsbDac.get((Context)this).status() + "})", null);
            this.pushOpen();
        }
        if (this.shell != null && this.shell.getVisibility() == View.VISIBLE) this.shell.refresh();
    }

    private void showNative() {
        showWeb();
    }

    private void showWeb() {
        if (this.shell != null) this.shell.setVisibility(View.GONE);
        this.mountWeb();
        if (this.web != null) {
            this.web.setVisibility(View.VISIBLE);
            if (!this.webLoaded) {
                this.webLoaded = true;
                try { this.web.loadUrl("file:///android_asset/www/index.html"); } catch (Throwable ignored) {}
            }
        }
    }

    @SuppressLint(value={"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void mountWeb() {
        if (this.web != null || this.root == null) return;
        try {
            this.web = new WebView((Context)this);
            this.tuneWebView(this.web);
            this.web.setWebViewClient(new WebViewClient(){
                public boolean shouldOverrideUrlLoading(WebView webView, String string) {
                    return this.outside(string);
                }
                public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
                    if (webResourceRequest == null || webResourceRequest.getUrl() == null) return false;
                    return this.outside(webResourceRequest.getUrl().toString());
                }
                private boolean outside(String string) {
                    return string != null && !string.startsWith("file:///android_asset/");
                }
            });
            this.web.addJavascriptInterface((Object)new Bridge(), "CarolNative");
            this.root.addView((View)this.web, (ViewGroup.LayoutParams)new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable throwable) {
            TextView textView = new TextView((Context)this);
            textView.setTextColor(-1185053);
            textView.setTextSize(16.0f);
            textView.setPadding(48, 96, 48, 48);
            textView.setText((CharSequence)"KZ Control needs Android System WebView. Update WebView, then open this app again.");
            this.root.addView((View)textView);
        }
    }

    static void bluetoothOff() {
        MainActivity a = liveUi;
        if (a == null || a.ble == null) return;
        a.runOnUiThread(() -> {
            if (a.ble != null) a.ble.radioOff();
        });
    }

    protected void onStop() {
        uiResumed = false;
        try {
            if (CarolService.session((Context)this) || CarolService.handshake || CarolService.linked((Context)this)) {
                CarolService.start((Context)this);
            } else {
                CarolService.stop((Context)this);
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        super.onStop();
    }

    protected void onPause() {
        if (this.web != null) {
            this.web.onPause();
        }
        UserState.snapshot((Context)this);
        super.onPause();
    }

    private boolean hasBlePerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return this.checkSelfPermission("android.permission.BLUETOOTH_SCAN") == 0 && this.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == 0;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return this.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == 0 || this.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == 0;
        }
        return true;
    }

    private boolean hasMic() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return this.checkSelfPermission("android.permission.RECORD_AUDIO") == 0;
    }

    private void requestMicThenFit() {
        if (this.hasMic()) {
            if (this.fit != null) {
                this.fit.start();
            }
            return;
        }
        this.fitAfterPerms = true;
        this.requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 8);
    }

    private void requestLaunchPerms() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        ArrayList<String> arrayList = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (this.checkSelfPermission("android.permission.BLUETOOTH_SCAN") != 0) {
                arrayList.add("android.permission.BLUETOOTH_SCAN");
            }
            if (this.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != 0) {
                arrayList.add("android.permission.BLUETOOTH_CONNECT");
            }
        }
        if (Build.VERSION.SDK_INT < 31 && this.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != 0) {
            arrayList.add("android.permission.ACCESS_FINE_LOCATION");
        }
        if (Build.VERSION.SDK_INT >= 33 && this.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            arrayList.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!arrayList.isEmpty()) {
            this.requestPermissions(arrayList.toArray(new String[0]), 7);
        }
    }

    private void requestBlePerms() {
        if (this.hasBlePerms()) {
            return;
        }
        this.requestLaunchPerms();
    }

    public void onRequestPermissionsResult(int n, String[] stringArray, int[] nArray) {
        super.onRequestPermissionsResult(n, stringArray, nArray);
        if (n == 7 && this.hasBlePerms() && this.scanAfterPerms) {
            this.scanAfterPerms = false;
            this.ble.startScan();
        }
        if (n == 8 && this.hasMic()) {
            if (this.fitAfterPerms) {
                this.fitAfterPerms = false;
                if (this.fit != null) {
                    this.fit.start();
                }
            }
            if (this.listenAfterPerms) {
                this.listenAfterPerms = false;
                if (this.micEq != null) {
                    this.micEq.startListen();
                }
            }
        }
        if (n == 9) {
            this.locAfterPerms = false;
            if (LastLost.hasLoc((Context)this)) {
                LastLost.setEnabled((Context)this, true);
            }
            if (this.web != null) {
                this.web.evaluateJavascript("window.onCarolEvent&&window.onCarolEvent({\"type\":\"lost\",\"status\":" + LastLost.status((Context)this) + "})", null);
            }
        }
    }

    public void onBackPressed() {
        this.handleBack();
    }

    private void handleBack() {
        if (this.web == null) {
            super.onBackPressed();
            return;
        }
        this.web.evaluateJavascript("window.carolBack&&window.carolBack()", string -> {
            if ("true".equals(string) || "\"true\"".equals(string)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 21) {
                this.finishAfterTransition();
            } else {
                this.finish();
            }
        });
    }

    protected void onDestroy() {
        if (liveUi == this) liveUi = null;
        if (this.web != null) {
            try {
                this.web.stopLoading();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            try {
                ViewParent viewParent = this.web.getParent();
                if (viewParent instanceof ViewGroup) {
                    ((ViewGroup)viewParent).removeView((View)this.web);
                }
            }
            catch (Throwable throwable) {
                // empty catch block
            }
            this.web.destroy();
            this.web = null;
        }
        if (this.fit != null) {
            this.fit.stop();
        }
        if (this.heli != null) {
            this.heli.stop();
        }
        if (this.micEq != null) {
            this.micEq.stopListen();
        }
        if (this.ble != null) {
            this.ble.disconnect();
        }
        super.onDestroy();
    }

    /** Tile and notification: cycle ANC only on a live chip. Never a demo write, never EQ bypass. */
    static void cycleAnc(android.content.Context ctx) {
        MainActivity a = liveUi;
        if (a == null || a.ble == null || !a.ble.chipReady()) return;
        String cur = "off";
        try {
            cur = ctx.getSharedPreferences("carol_ble", 0).getString("cache_anc", "off");
        } catch (Throwable ignored) {
        }
        if (cur == null || cur.length() == 0) cur = "off";
        String next = "off".equals(cur) ? "anc" : "anc".equals(cur) ? "transparency" : "off";
        a.ble.setAnc(next);
        try { MixTile.refresh(ctx); } catch (Throwable ignored) {}
    }

    /** Motion shift. Same mode write as a tap. No-op unless the chip is live. */
    static void applyAnc(String mode) {
        MainActivity a = liveUi;
        if (a == null || a.ble == null || !a.ble.chipReady()) return;
        if (mode == null) return;
        a.ble.setAnc(mode);
    }

    /** Notification: chip chirp when the radio answers, otherwise a phone beep on Classic. */
    static void pingFind(android.content.Context ctx) {
        MainActivity a = liveUi;
        if (a == null) return;
        if (a.ble != null && a.ble.chipReady()) a.ble.pingFind();
        else FindBeep.get(a).play("left");
    }

    private final class Bridge {
        private Bridge() {
        }

        @JavascriptInterface
        public boolean isNative() {
            return true;
        }

        @JavascriptInterface
        public void scan() {
            MainActivity.this.runOnUiThread(() -> {
                if (MainActivity.this.ble == null) {
                    return;
                }
                if (!MainActivity.this.hasBlePerms()) {
                    MainActivity.this.scanAfterPerms = true;
                    MainActivity.this.requestBlePerms();
                    MainActivity.this.web.evaluateJavascript("window.onCarolEvent && window.onCarolEvent({\"type\":\"error\",\"message\":\"Allow Nearby devices, then tap Scan again.\"})", null);
                    return;
                }
                MainActivity.this.ble.startScan();
            });
        }

        @JavascriptInterface
        public void retryLast() {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.retryLast());
        }

        @JavascriptInterface
        public void connectPaired() {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.connectPaired());
        }

        @JavascriptInterface
        public void stopScan() {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.stopScan());
        }

        @JavascriptInterface
        public void connect(String string) {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.connect(string));
        }

        @JavascriptInterface
        public void disconnect() {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.disconnect());
        }

        @JavascriptInterface
        public String getRoster() {
            return DeviceRoster.json((Context)MainActivity.this);
        }

        @JavascriptInterface
        public void switchDevice(String string) {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.switchTo(string));
        }

        @JavascriptInterface
        public void forgetDevice(String string) {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.ble.forget(string));
        }

        @JavascriptInterface
        public void setVolume(int n) {
            MainActivity.this.ble.setVolume(n);
        }

        @JavascriptInterface
        public boolean absVolOff() {
            return VolumeUnlock.isDisabled((Context)MainActivity.this);
        }

        @JavascriptInterface
        public boolean tryUnlock() {
            boolean bl = VolumeUnlock.tryDisable((Context)MainActivity.this);
            MainActivity.this.ble.emitAbsVol();
            return bl;
        }

        @JavascriptInterface
        public void openAbsVolSettings() {
            VolumeUnlock.openSettings((Context)MainActivity.this);
        }

        @JavascriptInterface
        public void showNative() {
            MainActivity.this.runOnUiThread(MainActivity.this::showNative);
        }

        @JavascriptInterface
        public void setMotionAnc(boolean on) {
            MotionAnc.setEnabled(MainActivity.this, on);
        }

        @JavascriptInterface
        public void setAnc(String string) {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.setAnc(string);
            }
        }

        @JavascriptInterface
        public void setStrength(int pct) {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.setStrength(pct);
            }
        }

        @JavascriptInterface
        public void setTransStrength(int pct) {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.setTransStrength(pct);
            }
        }

        @JavascriptInterface
        public void restoreAnc() {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.restoreAnc();
            }
        }

        @JavascriptInterface
        public void matchEars(String string) {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.matchEars(string);
            }
        }

        @JavascriptInterface
        public void saveAnc(String string) {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.saveAncMode(string);
            }
        }

        @JavascriptInterface
        public String linkDebug() {
            return MainActivity.this.ble != null ? MainActivity.this.ble.linkDebug() : "{}";
        }

        @JavascriptInterface
        public void refreshAnc() {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.refreshAnc();
            }
        }

        @JavascriptInterface
        public String firmwareInfo() {
            return MainActivity.this.ble.firmwareJson();
        }

        @JavascriptInterface
        public void checkFirmware() {
            MainActivity.this.ble.checkFirmware();
        }

        @JavascriptInterface
        public boolean kzAudioInstalled() {
            try {
                MainActivity.this.getPackageManager().getPackageInfo("com.kzhifi.earphone", 0);
                return true;
            }
            catch (Exception exception) {
                return false;
            }
        }

        @JavascriptInterface
        public void openKzAudio() {
            MainActivity.this.runOnUiThread(() -> {
                try {
                    Intent intent = MainActivity.this.getPackageManager().getLaunchIntentForPackage("com.kzhifi.earphone");
                    if (intent != null) {
                        intent.addFlags(0x10000000);
                        MainActivity.this.startActivity(intent);
                        return;
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
                try {
                    MainActivity.this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse((String)"market://details?id=com.kzhifi.earphone")).addFlags(0x10000000));
                    return;
                }
                catch (Exception exception) {
                    try {
                        MainActivity.this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse((String)"https://play.google.com/store/apps/details?id=com.kzhifi.earphone")).addFlags(0x10000000));
                    }
                    catch (Exception exception2) {
                        // empty catch block
                    }
                    return;
                }
            });
        }

        @JavascriptInterface
        public void setWork(String string) {
            MainActivity.this.ble.setWork(string);
        }

        @JavascriptInterface
        public void setEq(int n, String string) {
            int[] nArray = this.parseBands(string);
            MainActivity.this.ble.setEq(n, nArray);
        }

        @JavascriptInterface
        public void setFind(String string) {
            if (MainActivity.this.ble != null && MainActivity.this.ble.chipReady()) {
                FindBeep.get(MainActivity.this).stop();
                MainActivity.this.ble.setFind(string);
            } else {
                FindBeep.get(MainActivity.this).play(string);
            }
        }

        @JavascriptInterface
        public void setKey(int n, int n2, int n3) {
            MainActivity.this.ble.setKey(n, n2, n3);
        }

        @JavascriptInterface
        public void setKeys(String string) {
            try {
                JSONArray jSONArray = new JSONArray(string);
                byte[] byArray = new byte[jSONArray.length()];
                for (int i = 0; i < jSONArray.length(); ++i) {
                    byArray[i] = (byte)jSONArray.getInt(i);
                }
                MainActivity.this.ble.setKeys(byArray);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }

        @JavascriptInterface
        public void absVolConfirmed() {
            VolumeUnlock.markUserEnabled((Context)MainActivity.this);
            MainActivity.this.ble.emitAbsVol();
        }

        @JavascriptInterface
        public void setLdac(boolean bl) {
            MainActivity.this.ble.setFlag(23, bl);
        }

        @JavascriptInterface
        public void setPrompt(boolean bl) {
            MainActivity.this.ble.setFlag(21, bl);
        }

        @JavascriptInterface
        public void setDual(boolean bl) {
            if (bl) {
                try {
                    CodecCtrl.get((Context)MainActivity.this).dropLdac(DeviceRoster.activeModel((Context)MainActivity.this));
                }
                catch (Exception exception) {
                    // empty catch block
                }
                MainActivity.this.ble.setFlag(23, false);
            }
            MainActivity.this.ble.setFlag(22, bl);
        }

        @JavascriptInterface
        public void setInEar(boolean bl) {
            if (MainActivity.this.ble != null) {
                MainActivity.this.ble.setInEar(bl);
            }
        }

        @JavascriptInterface
        public void setLeAudio(boolean bl) {
            MainActivity.this.ble.setFlag(24, bl);
        }

        @JavascriptInterface
        public void setMic(String string) {
            MainActivity.this.ble.setMic(string);
        }

        @JavascriptInterface
        public void factoryReset() {
            MainActivity.this.ble.factoryReset();
        }

        @JavascriptInterface
        public String setSpatial(String string) {
            String string2 = MainActivity.this.spatial.apply(string);
            MainActivity.this.syncSession();
            MixTile.refresh((Context)MainActivity.this);
            return string2;
        }

        @JavascriptInterface
        public String getSpatial() {
            return MainActivity.this.spatial.mode();
        }

        @JavascriptInterface
        public String setSpatialTune(String string) {
            String string2 = MainActivity.this.spatial.applyTune(string);
            MixTile.refresh((Context)MainActivity.this);
            MainActivity.this.syncSession();
            return string2;
        }

        @JavascriptInterface
        public void setBrightness(int pct) {
            MainActivity.this.ble.setLogo(pct);
        }

        @JavascriptInterface
        public void commitBrightness(int pct) {
            MainActivity.this.ble.commitLogo(pct);
        }

        @JavascriptInterface
        public boolean setEqBypass(boolean bl) {
            MainActivity.this.spatial.setBypass(bl);
            MixTile.refresh((Context)MainActivity.this);
            MainActivity.this.syncSession();
            return MainActivity.this.spatial.bypass();
        }

        @JavascriptInterface
        public boolean getEqBypass() {
            return MainActivity.this.spatial.bypass();
        }

        @JavascriptInterface
        public void shareText(String string, String string2) {
            MainActivity.this.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent("android.intent.action.SEND");
                    intent.setType("text/plain");
                    intent.putExtra("android.intent.extra.SUBJECT", string == null ? "KZ profile" : string);
                    intent.putExtra("android.intent.extra.TEXT", string2 == null ? "" : string2);
                    MainActivity.this.startActivity(Intent.createChooser((Intent)intent, (CharSequence)string));
                }
                catch (Exception exception) {
                    // empty catch block
                }
            });
        }

        @JavascriptInterface
        public String getSpatialTune() {
            return MainActivity.this.spatial.tuneJson();
        }

        @JavascriptInterface
        public String getProfiles() {
            return MainActivity.this.getSharedPreferences("carol_ble", 0).getString("spatial_profiles", "");
        }

        @JavascriptInterface
        public String getCatalog() {
            return KzCatalog.json();
        }

        @JavascriptInterface
        public String getUsb() {
            return UsbDac.get((Context)MainActivity.this).status();
        }

        @JavascriptInterface
        public String setUsbExclusive(boolean bl) {
            return UsbDac.get((Context)MainActivity.this).setExclusive(bl);
        }

        @JavascriptInterface
        public void requestUsb() {
            MainActivity.this.runOnUiThread(() -> UsbDac.get((Context)MainActivity.this).requestPerms());
        }

        @JavascriptInterface
        public String getCodec(String string) {
            return CodecCtrl.get((Context)MainActivity.this).status(string);
        }

        @JavascriptInterface
        public String setCodec(String string, String string2, int n, int n2, int n3) {
            String string3 = CodecCtrl.get((Context)MainActivity.this).apply(string, string2, n, n2, n3);
            if ("ldac".equalsIgnoreCase(string2)) {
                MainActivity.this.ble.setFlag(23, true);
            } else if (string2 != null && !"pcm".equalsIgnoreCase(string2)) {
                MainActivity.this.ble.setFlag(23, false);
            }
            return string3;
        }

        @JavascriptInterface
        public void setProfiles(String string) {
            MainActivity.this.getSharedPreferences("carol_ble", 0).edit().putString("spatial_profiles", string == null ? "" : string).apply();
            UserState.markDirty();
        }

        @JavascriptInterface
        public String migrateWeb(String string) {
            return UserState.migrateWeb((Context)MainActivity.this, string);
        }

        @JavascriptInterface
        public String getLastDevice() {
            return UserState.lastDevice((Context)MainActivity.this);
        }

        @JavascriptInterface
        public String getRadioCache() {
            return UserState.radioCache((Context)MainActivity.this);
        }

        @JavascriptInterface
        public String getLostLog() {
            return LastLost.status((Context)MainActivity.this);
        }

        @JavascriptInterface
        public String setLost(boolean bl) {
            if (!bl) {
                LastLost.setEnabled((Context)MainActivity.this, false);
                return LastLost.status((Context)MainActivity.this);
            }
            MainActivity.this.runOnUiThread(() -> {
                if (LastLost.hasLoc((Context)MainActivity.this)) {
                    LastLost.setEnabled((Context)MainActivity.this, true);
                    if (MainActivity.this.web != null) {
                        MainActivity.this.web.evaluateJavascript("window.onCarolEvent&&window.onCarolEvent({\"type\":\"lost\",\"status\":" + LastLost.status((Context)MainActivity.this) + "})", null);
                    }
                    return;
                }
                MainActivity.this.locAfterPerms = true;
                if (Build.VERSION.SDK_INT >= 23) {
                    MainActivity.this.requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"}, 9);
                }
            });
            return LastLost.status((Context)MainActivity.this);
        }

        @JavascriptInterface
        public void requestLoc() {
            this.setLost(true);
        }

        @JavascriptInterface
        public void openLost() {
            MainActivity.this.runOnUiThread(() -> LastLost.openLast((Context)MainActivity.this));
        }

        @JavascriptInterface
        public void openLostAt(double d, double d2) {
            MainActivity.this.runOnUiThread(() -> LastLost.openAt((Context)MainActivity.this, d, d2));
        }

        @JavascriptInterface
        public String getMicEq(String string) {
            return MainActivity.this.micEq.loadModel(string);
        }

        @JavascriptInterface
        public String setMicEq(String string, String string2) {
            return MainActivity.this.micEq.apply(string, string2);
        }

        @JavascriptInterface
        public void setMicMute(boolean bl) {
            MainActivity.this.micEq.setMute(bl);
            try {
                MainActivity.this.ble.setMic(bl ? "mute" : "auto");
            }
            catch (Exception exception) {
                // empty catch block
            }
        }

        @JavascriptInterface
        public void startMicListen() {
            MainActivity.this.runOnUiThread(() -> {
                if (!MainActivity.this.hasMic()) {
                    MainActivity.this.listenAfterPerms = true;
                    if (Build.VERSION.SDK_INT >= 23) {
                        MainActivity.this.requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 8);
                    }
                    return;
                }
                MainActivity.this.micEq.startListen();
            });
        }

        @JavascriptInterface
        public void stopMicListen() {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.micEq.stopListen());
        }

        @JavascriptInterface
        public void startHeli(String string) {
            if (MainActivity.this.heli != null) {
                MainActivity.this.heli.start(string);
            }
        }

        @JavascriptInterface
        public void setHeli(String string) {
            if (MainActivity.this.heli != null) {
                MainActivity.this.heli.layout(string);
            }
        }

        @JavascriptInterface
        public void stopHeli() {
            if (MainActivity.this.heli != null) {
                MainActivity.this.heli.stop();
            }
        }

        @JavascriptInterface
        public void playHear(int hz, int step, String side) {
            HearTone.get(MainActivity.this).play(hz, step, "right".equals(side));
        }

        @JavascriptInterface
        public void stopHear() {
            HearTone.get(MainActivity.this).stop();
        }

        @JavascriptInterface
        public void startFit() {
            MainActivity.this.runOnUiThread(() -> MainActivity.this.requestMicThenFit());
        }

        @JavascriptInterface
        public void stopFit() {
            MainActivity.this.runOnUiThread(() -> {
                if (MainActivity.this.fit != null) {
                    MainActivity.this.fit.stop();
                }
            });
        }

        @JavascriptInterface
        public void openSoundSettings() {
            MainActivity.this.runOnUiThread(() -> {
                String[] stringArray;
                for (String string : stringArray = new String[]{"com.sonymobile.settings.SOUND_SETTINGS", "android.settings.SOUND_SETTINGS", "android.settings.SETTINGS"}) {
                    try {
                        MainActivity.this.startActivity(new Intent(string).addFlags(0x10000000));
                        return;
                    }
                    catch (Exception exception) {
                    }
                }
            });
        }

        private int[] parseBands(String string) {
            try {
                JSONArray jSONArray = new JSONArray(string);
                int[] nArray = new int[jSONArray.length()];
                for (int i = 0; i < jSONArray.length(); ++i) {
                    nArray[i] = jSONArray.getInt(i);
                }
                return nArray;
            }
            catch (Exception exception) {
                return new int[10];
            }
        }
    }
}
