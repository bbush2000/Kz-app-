package com.jieli.bluetooth.utils;

/** JNI target for libjl_bluetooth.so crc16. */
public class CryptoUtil {
    public static native short crc16(byte[] data, short seed);
}
