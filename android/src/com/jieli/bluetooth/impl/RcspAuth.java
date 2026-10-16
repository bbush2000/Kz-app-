package com.jieli.bluetooth.impl;

/**
 * JNI names must match libjl_bluetooth.so RegisterNatives.
 */
public class RcspAuth {
    static {
        try {
            System.loadLibrary("jl_enc");
        } catch (Throwable ignored) {
        }
        try {
            System.loadLibrary("jl_bluetooth");
        } catch (Throwable ignored) {
        }
    }

    public native boolean nativeInit();

    public native byte[] getRandomAuthData();

    public native byte[] getEncryptedAuthData(byte[] data);

    public native int setLinkKey(byte[] key);
}
