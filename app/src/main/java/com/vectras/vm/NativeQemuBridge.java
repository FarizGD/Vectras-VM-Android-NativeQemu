package com.vectras.vm;

import android.util.Log;

/** JNI bridge for in-process Android-native QEMU shared libraries. */
public final class NativeQemuBridge {
    private static final String TAG = "NativeQemuBridge";
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("native_helper");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
            Log.e(TAG, "Unable to load native_helper", error);
        }
    }

    private NativeQemuBridge() {}

    public static boolean isAvailable() {
        return loaded;
    }

    public static native String start(String libraryPath, String[] argv);
    public static native boolean isRunning();
    public static native boolean requestStop();
}
