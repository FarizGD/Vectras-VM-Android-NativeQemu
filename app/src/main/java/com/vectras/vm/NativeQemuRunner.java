package com.vectras.vm;

import android.content.Context;
import android.util.Log;

import com.vectras.vm.logger.VectrasStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes QEMU directly as an Android native process instead of entering the
 * proot distro first.
 *
 * Native QEMU executables are packaged as executable shared-library payloads:
 *   libqemu-system-i386.so
 *   libqemu-system-x86_64.so
 *   libqemu-system-aarch64.so
 *   libqemu-system-ppc.so
 *
 * Android extracts those files into ApplicationInfo.nativeLibraryDir. Naming
 * them as lib*.so lets the package manager place them in an executable native
 * library directory while still allowing them to contain a normal PIE QEMU
 * executable.
 */
public final class NativeQemuRunner {
    private static final String TAG = "NativeQemuRunner";
    private static final int MAX_LOG_SIZE = 200_000;

    private static final Pattern QEMU_COMMAND_PATTERN = Pattern.compile(
            "(^|\\s)(qemu-system-(?:i386|x86_64|aarch64|ppc))(?=\\s|$)"
    );

    private static volatile Process currentProcess;

    private NativeQemuRunner() {
    }

    public interface Callback {
        void onRunning(String command, String newLine);
        void onFinished(String command, String log, int status);
        void onError(String command, Exception exception);
    }

    public static boolean isQemuCommand(String command) {
        return command != null && QEMU_COMMAND_PATTERN.matcher(command).find();
    }

    /**
     * Returns true only when the command is a QEMU command and the matching
     * native executable has actually been packaged in this APK.
     */
    public static boolean canExecute(Context context, String command) {
        if (context == null || !isQemuCommand(command)) return false;

        String qemuName = extractQemuName(command);
        if (qemuName == null) return false;

        File executable = getNativeExecutable(context, qemuName);
        boolean available = executable.isFile() && executable.canExecute();

        if (!available) {
            Log.i(TAG, "Native QEMU payload not available: " + executable.getAbsolutePath());
        }

        return available;
    }

    public static File getNativeExecutable(Context context, String qemuName) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        return new File(nativeDir, "lib" + qemuName + ".so");
    }

    public static void execute(Context context, String command, Callback callback) {
        new Thread(() -> executeBlocking(context, command, callback), "NativeQemu").start();
    }

    private static void executeBlocking(Context context, String command, Callback callback) {
        try {
            String qemuName = extractQemuName(command);
            if (qemuName == null) {
                throw new IllegalArgumentException("No supported qemu-system executable found in command");
            }

            File executable = getNativeExecutable(context, qemuName);
            if (!executable.isFile()) {
                throw new IllegalStateException("Native QEMU executable is missing: " + executable.getAbsolutePath());
            }

            String nativeCommand = replaceQemuExecutable(command, executable.getAbsolutePath());
            Log.i(TAG, "Starting native QEMU: " + nativeCommand);

            ProcessBuilder processBuilder = new ProcessBuilder("/system/bin/sh", "-c", nativeCommand);
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(context.getFilesDir());

            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            String oldLdPath = processBuilder.environment().get("LD_LIBRARY_PATH");
            processBuilder.environment().put(
                    "LD_LIBRARY_PATH",
                    nativeLibDir + (oldLdPath == null || oldLdPath.isEmpty() ? "" : ":" + oldLdPath)
            );
            processBuilder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
            processBuilder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            processBuilder.environment().put("XDG_RUNTIME_DIR", context.getCacheDir().getAbsolutePath());

            Process process = processBuilder.start();
            currentProcess = process;

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    VectrasStatus.logError(line);
                    output.append(line).append('\n');

                    if (output.length() > MAX_LOG_SIZE) {
                        output.delete(0, output.length() - MAX_LOG_SIZE);
                    }

                    if (callback != null) callback.onRunning(command, line);
                }
            }

            int status = process.waitFor();
            if (callback != null) callback.onFinished(command, output.toString(), status);
        } catch (Exception e) {
            Log.e(TAG, "Native QEMU failed", e);
            if (callback != null) callback.onError(command, e);
        } finally {
            currentProcess = null;
        }
    }

    public static synchronized void stop() {
        Process process = currentProcess;
        if (process == null) return;

        try {
            process.destroy();
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to stop native QEMU cleanly", e);
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
                // Nothing else we can do here.
            }
        } finally {
            currentProcess = null;
        }
    }

    public static boolean isRunning() {
        Process process = currentProcess;
        return process != null && process.isAlive();
    }

    private static String extractQemuName(String command) {
        if (command == null) return null;
        Matcher matcher = QEMU_COMMAND_PATTERN.matcher(command);
        return matcher.find() ? matcher.group(2).toLowerCase(Locale.ROOT) : null;
    }

    private static String replaceQemuExecutable(String command, String executablePath) {
        Matcher matcher = QEMU_COMMAND_PATTERN.matcher(command);
        if (!matcher.find()) return command;

        String replacement = matcher.group(1) + shellQuote(executablePath);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
