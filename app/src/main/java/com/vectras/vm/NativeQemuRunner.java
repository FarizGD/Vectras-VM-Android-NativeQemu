package com.vectras.vm;

import android.content.Context;
import android.util.Log;

import com.vectras.vm.logger.VectrasStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs packaged Android-native QEMU without entering the legacy proot distro. */
public final class NativeQemuRunner {
    private static final String TAG = "NativeQemuRunner";
    private static final int MAX_LOG_SIZE = 200_000;
    private static final Pattern QEMU_COMMAND_PATTERN = Pattern.compile(
            "(^|\\s)(qemu-system-(?:i386|x86_64|aarch64|ppc)|qemu-img)(?=\\s|$)"
    );

    private static volatile Process currentProcess;

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
        if (context == null || !isQemuCommand(command)) return false;
        String qemuName = extractQemuName(command);
        if (qemuName == null) return false;
        File payload = getNativeLibrary(context, qemuName);
        boolean available = payload.isFile();
        if (!available) Log.i(TAG, "Native QEMU payload not available: " + payload.getAbsolutePath());
        return available;
    }

    /** Android extracts lib*.so entries into an executable native-library directory. */
    public static File getNativeLibrary(Context context, String qemuName) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "lib" + qemuName + ".so");
    }

    public static void execute(Context context, String command, Callback callback) {
        new Thread(() -> executeBlocking(context, command, callback), "NativeQemu").start();
    }

    public static void executeBlocking(Context context, String command, Callback callback) {
        try {
            String qemuName = extractQemuName(command);
            if (qemuName == null) throw new IllegalArgumentException("No supported QEMU executable found");

            File payload = getNativeLibrary(context, qemuName);
            if (!payload.isFile()) throw new IllegalStateException("Native QEMU payload is missing: " + payload);

            String invocation = extractQemuInvocation(command);
            String[] parsed = splitShellCommand(invocation);
            if (parsed.length == 0) throw new IllegalArgumentException("QEMU command is empty");

            File runtime = NativeQemuRuntime.ensureInstalled(context);
            List<String> argv = new ArrayList<>(Arrays.asList(parsed));
            argv.set(0, payload.getAbsolutePath());

            // qemu-system-* needs the bundled firmware directory. qemu-img does not
            // understand the -L option, so never append it to utility commands.
            if (qemuName.startsWith("qemu-system-")) {
                normalizeLegacyCdromParams(argv);
                normalizeAudioBackendForAndroid(argv);

                File firmware = new File(runtime, "share/qemu");
                if (firmware.isDirectory() && !containsOption(argv, "-L")) {
                    argv.add("-L");
                    argv.add(firmware.getAbsolutePath());
                }
            }

            try {
                runNativeProcess(context, command, argv, runtime, callback);
            } catch (java.io.IOException processError) {
                // A future build may package QEMU as a true shared object rather than a PIE
                // executable. Keep the independent JNI loader as a compatible fallback.
                if (!NativeQemuBridge.isAvailable()) throw processError;
                Log.w(TAG, "Direct QEMU exec failed, trying JNI loader", processError);
                String[] jniArgv = argv.toArray(new String[0]);
                String result = NativeQemuBridge.start(payload.getAbsolutePath(), jniArgv);
                int status = result == null || result.isEmpty() ? 0 : 1;
                if (callback != null) callback.onFinished(command, result == null ? "" : result, status);
            }
        } catch (Exception e) {
            Log.e(TAG, "Native QEMU failed", e);
            if (callback != null) callback.onError(command, e);
        }
    }

    /**
     * Older Vectras command generation used values such as media=cdromdrive1.
     * QEMU only accepts media=disk or media=cdrom. Preserve the intended drive id
     * while converting the invalid media value before starting native QEMU.
     */
    private static void normalizeLegacyCdromParams(List<String> argv) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (!"-drive".equals(argv.get(i))) continue;

            String value = argv.get(i + 1);
            Matcher matcher = Pattern.compile("(^|,)media=(cdromdrive[0-9]+)(?=,|$)").matcher(value);
            if (!matcher.find()) continue;

            String driveId = matcher.group(2);
            String normalized = matcher.replaceFirst("$1media=cdrom,id=" + driveId);
            argv.set(i + 1, normalized);
            Log.i(TAG, "Normalized legacy CD-ROM drive option: " + normalized);
        }
    }

    /**
     * The Termux-built QEMU runtime has no Android host audio service to open directly.
     * Keep the guest sound device but replace configured host backends with QEMU's
     * built-in silent backend. This prevents audio initialization from aborting the VM.
     */
    private static void normalizeAudioBackendForAndroid(List<String> argv) {
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            if ("-audiodev".equals(arg) && i + 1 < argv.size()) {
                String value = argv.get(i + 1);
                String id = null;
                for (String part : value.split(",")) {
                    if (part.startsWith("id=")) {
                        id = part.substring(3);
                        break;
                    }
                }
                argv.set(i + 1, id == null || id.isEmpty() ? "none,id=audio0" : "none,id=" + id);
            } else if ("-audio".equals(arg) && i + 1 < argv.size()) {
                argv.set(i + 1, "none");
            }
        }
    }

    private static void runNativeProcess(Context context, String originalCommand, List<String> argv,
                                         File runtime, Callback callback) throws Exception {
        Log.i(TAG, "Starting native Android QEMU: " + argv.get(0));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.redirectErrorStream(true);
        builder.directory(context.getFilesDir());

        File runtimeLib = new File(runtime, "lib");
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        builder.environment().put("LD_LIBRARY_PATH", nativeLibDir + ":" + runtimeLib.getAbsolutePath());
        builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        builder.environment().put("XDG_RUNTIME_DIR", context.getCacheDir().getAbsolutePath());
        builder.environment().put("QEMU_MODULE_DIR", new File(runtimeLib, "qemu").getAbsolutePath());
        if (originalCommand.contains("export DISPLAY=:0")) builder.environment().put("DISPLAY", ":0");

        Process process = builder.start();
        currentProcess = process;
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                VectrasStatus.logError(line);
                output.append(line).append('\n');
                if (output.length() > MAX_LOG_SIZE) output.delete(0, output.length() - MAX_LOG_SIZE);
                if (callback != null) callback.onRunning(originalCommand, line);
            }
        }
        int status = process.waitFor();
        currentProcess = null;
        if (callback != null) callback.onFinished(originalCommand, output.toString(), status);
    }

    public static synchronized void stop() {
        Process process = currentProcess;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                currentProcess = null;
            }
            return;
        }
        if (NativeQemuBridge.isAvailable() && NativeQemuBridge.isRunning()) {
            if (!NativeQemuBridge.requestStop()) Log.w(TAG, "Native QEMU refused shutdown request");
        }
    }

    public static boolean isRunning() {
        Process process = currentProcess;
        return (process != null && process.isAlive()) ||
                (NativeQemuBridge.isAvailable() && NativeQemuBridge.isRunning());
    }

    private static boolean containsOption(List<String> args, String option) {
        for (String arg : args) if (option.equals(arg)) return true;
        return false;
    }

    private static String extractQemuName(String command) {
        if (command == null) return null;
        Matcher matcher = QEMU_COMMAND_PATTERN.matcher(command);
        return matcher.find() ? matcher.group(2).toLowerCase(Locale.ROOT) : null;
    }

    /** Pull the actual QEMU invocation out of MainStartVM's logging/cleanup shell wrapper. */
    static String extractQemuInvocation(String command) {
        Matcher matcher = QEMU_COMMAND_PATTERN.matcher(command);
        if (!matcher.find()) throw new IllegalArgumentException("No QEMU invocation found");
        int start = matcher.start(2);
        int end = command.length();

        String[] markers = {" && echo '" + com.vectras.vm.main.core.MainStartVM.TAG_FINISHED_WITHOUT_ERROR, "\nrm -r "};
        for (String marker : markers) {
            int candidate = command.indexOf(marker, start);
            if (candidate >= 0 && candidate < end) end = candidate;
        }

        String result = command.substring(start, end).trim();
        // MainStartVM can wrap X11 commands in bash -c "...". That outer closing
        // quote is not part of QEMU argv. Remove it only when quote parity is odd.
        if (result.endsWith("\"") && hasOddUnescapedDoubleQuotes(result)) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean hasOddUnescapedDoubleQuotes(String value) {
        int count = 0;
        boolean escape = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') count++;
        }
        return (count & 1) == 1;
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
            if (escape) { current.append(c); escape = false; continue; }
            if (c == '\\' && !single) { escape = true; continue; }
            if (c == '\'' && !quoted) { single = !single; continue; }
            if (c == '"' && !single) { quoted = !quoted; continue; }
            if (Character.isWhitespace(c) && !single && !quoted) {
                if (current.length() > 0) { args.add(current.toString()); current.setLength(0); }
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