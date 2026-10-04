package com.vectras.vm;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs packaged Android-native QEMU shared libraries without proot. */
public final class NativeQemuRunner {
    private static final String TAG = "NativeQemuRunner";
    private static final Pattern QEMU_COMMAND_PATTERN = Pattern.compile(
            "(^|\\s)(qemu-system-(?:i386|x86_64|aarch64|ppc))(?=\\s|$)"
    );

    private NativeQemuRunner() {}

    public interface Callback {
        void onRunning(String command, String newLine);
        void onFinished(String command, String log, int status);
        void onError(String command, Exception exception);
    }

    public static boolean isQemuCommand(String command) {
        return command != null && QEMU_COMMAND_PATTERN.matcher(command).find();
    }

    public static boolean canExecute(Context context, String command) {
        if (context == null || !NativeQemuBridge.isAvailable() || !isQemuCommand(command)) return false;

        String qemuName = extractQemuName(command);
        if (qemuName == null) return false;

        File library = getNativeLibrary(context, qemuName);
        boolean available = library.isFile();
        if (!available) Log.i(TAG, "Native QEMU library not available: " + library.getAbsolutePath());
        return available;
    }

    public static File getNativeLibrary(Context context, String qemuName) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "lib" + qemuName + ".so");
    }

    public static void execute(Context context, String command, Callback callback) {
        new Thread(() -> executeBlocking(context, command, callback), "NativeQemu").start();
    }

    private static void executeBlocking(Context context, String command, Callback callback) {
        try {
            String qemuName = extractQemuName(command);
            if (qemuName == null) throw new IllegalArgumentException("No supported qemu-system executable found");

            File library = getNativeLibrary(context, qemuName);
            if (!library.isFile()) throw new IllegalStateException("Native QEMU library is missing: " + library);

            String[] argv = splitShellCommand(command);
            if (argv.length == 0) throw new IllegalArgumentException("QEMU command is empty");

            Log.i(TAG, "Starting in-process native QEMU: " + library.getAbsolutePath());
            String result = NativeQemuBridge.start(library.getAbsolutePath(), argv);
            int status = result == null || result.isEmpty() ? 0 : 1;
            if (callback != null) callback.onFinished(command, result == null ? "" : result, status);
        } catch (Exception e) {
            Log.e(TAG, "Native QEMU failed", e);
            if (callback != null) callback.onError(command, e);
        }
    }

    public static synchronized void stop() {
        if (NativeQemuBridge.isAvailable() && NativeQemuBridge.isRunning()) {
            if (!NativeQemuBridge.requestStop()) Log.w(TAG, "Native QEMU refused shutdown request");
        }
    }

    public static boolean isRunning() {
        return NativeQemuBridge.isAvailable() && NativeQemuBridge.isRunning();
    }

    private static String extractQemuName(String command) {
        if (command == null) return null;
        Matcher matcher = QEMU_COMMAND_PATTERN.matcher(command);
        return matcher.find() ? matcher.group(2).toLowerCase(Locale.ROOT) : null;
    }

    /** Small POSIX-like tokenizer for the generated QEMU command line. */
    static String[] splitShellCommand(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean quoted = false;
        boolean escape = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }
            if (c == '\\' && !single) {
                escape = true;
                continue;
            }
            if (c == '\'' && !quoted) {
                single = !single;
                continue;
            }
            if (c == '"' && !single) {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !single && !quoted) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (escape) current.append('\\');
        if (single || quoted) throw new IllegalArgumentException("Unterminated quote in QEMU command");
        if (current.length() > 0) args.add(current.toString());
        return args.toArray(new String[0]);
    }
}
