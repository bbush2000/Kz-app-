package com.carol.control;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.view.View;
import android.webkit.WebView;

/** API shims so the same APK runs from Android 7 (24) through 15+. */
final class Compat {
    static AudioTrack mediaStream(int sr, int channelMask, int bufferBytes) {
        AudioAttributes attr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask)
                .build();
        return new AudioTrack.Builder()
                .setAudioAttributes(attr)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(bufferBytes, 2048))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    static void webLayer(WebView web, Context ctx) {
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    static int piFlags() {
        return Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
