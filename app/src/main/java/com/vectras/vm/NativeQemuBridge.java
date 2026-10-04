package com.vectras.vm;

import android.util.Log;

/**
 * JNI bridge for Android-native QEMU shared libraries.
 *
 * QEMU is loaded with dlopen() from the app native-library directory and then
 * entered through qemu_init()/qemu_main_loop()/qemu_cleanup(). This keeps the
 * VM completely outside the legacy proot userspace.
 */
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

    private NativeQemuBridge() {
    }

    public static boolean isAvailable() {
        return loaded;
    }

    /**
     * Runs one QEMU instance on the calling thread.
     *
     * @param libraryPath absolute path to libqemu-system-*.so
     * @param argv complete QEMU argv including argv[0]
     * @return empty string on a clean shutdown, otherwise a diagnostic string
     */
    public static native String start(String libraryPath, String[] argv);

    /** Returns whether the JNI loader currently owns a running QEMU instance. */
    public static native boolean isRunning();
}
