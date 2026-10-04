package com.vectras.vm;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Installs the read-only QEMU data files bundled under assets/native-qemu. */
public final class NativeQemuRuntime {
    private static final String TAG = "NativeQemuRuntime";
    private static final String ASSET_ROOT = "native-qemu";
    private static final String VERSION_FILE = ".runtime-version";
    private static final String VERSION = "termux-qemu-11";

    private NativeQemuRuntime() {}

    public static synchronized File ensureInstalled(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "native-qemu");
        File marker = new File(root, VERSION_FILE);
        if (marker.isFile() && VERSION.equals(readSmallFile(marker))) return root;

        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Unable to create native QEMU runtime directory: " + root);
        }

        copyAssetTree(context.getAssets(), ASSET_ROOT, root);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Log.i(TAG, "Installed native QEMU runtime to " + root);
        return root;
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File destination) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null) throw new IOException("Unable to list asset path: " + assetPath);

        if (children.length == 0) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create " + parent);
            }
            try (InputStream input = assets.open(assetPath);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return;
        }

        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Unable to create " + destination);
        }
        for (String child : children) {
            copyAssetTree(assets, assetPath + "/" + child, new File(destination, child));
        }
    }

    private static String readSmallFile(File file) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 128)];
            int read = input.read(bytes);
            return read <= 0 ? "" : new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w(TAG, "Unable to remove stale runtime path: " + file);
    }
}
