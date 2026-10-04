package com.vectras.vm.utils;

import android.app.Activity;
import android.content.Context;

import com.vectras.vm.NativeQemuRunner;
import com.vectras.vm.VectrasApp;
import com.vectras.vterm.Terminal2;

import java.util.concurrent.atomic.AtomicReference;

public class CommandUtils {
    public static String createForSelectedMirror(boolean _https, String _url, String _beforemain) {
        String version = "v3.19";
        String command = "echo \"\" > /etc/apk/repositories && sed -i -e \"1ihttps://xssFjnj58Id/yttGkok69Je/"
                + version + "/community\" /etc/apk/repositories && sed -i -e \"1ihttps://xssFjnj58Id/yttGkok69Je/" + version + "/main\" /etc/apk/repositories";

        command = command.replaceAll("/yttGkok69Je", _beforemain);
        if (!_https)
            command = command.replaceAll("https://", "http://");
        return command.replaceAll("xssFjnj58Id", _url);
    }

    public static void run(String _command, boolean _isShowResult, Activity _activity) {
        new Terminal2(_activity).execute(_command);
    }

    public static String getQemuVersionName(Context context) {
        String qemuVersion = getQemuVersion(context);

        if (qemuVersion.toLowerCase().contains("failed") || qemuVersion.toLowerCase().contains("not found"))
            return "";

        return (qemuVersion.contains("Error") ? qemuVersion.substring(0, qemuVersion.indexOf("Error")) : qemuVersion) + (is3dfxVersion(context) ? " - 3dfx" : "");
    }

    public static String getQemuVersion(Context context) {
        if (VectrasApp.getContext() == null) return "Unknow";

        if (NativeQemuRunner.canExecute(context, "qemu-system-x86_64 --version")) {
            String output = runNativeVersion(context);
            if (output.isEmpty()) return "Unknow";
            String firstLine = output.split("\\R", 2)[0].trim();
            String[] parts = firstLine.split("\\s+");
            return parts.length >= 4 ? parts[3] : firstLine;
        }

        return new Terminal2(context)
                .executeOnThisThread("qemu-system-x86_64 --version | head -n1 | awk '{print $4}'")
                .replaceAll("\n", "");
    }

    public static boolean is3dfxVersion(Context context) {
        if (VectrasApp.getContext() == null) return false;
        if (NativeQemuRunner.canExecute(context, "qemu-system-x86_64 --version")) {
            return runNativeVersion(context).contains("3dfx");
        }
        return new Terminal2(context).executeOnThisThread("qemu-system-x86_64 --version").contains("3dfx");
    }

    private static String runNativeVersion(Context context) {
        AtomicReference<String> output = new AtomicReference<>("");
        NativeQemuRunner.executeBlocking(context, "qemu-system-x86_64 --version", new NativeQemuRunner.Callback() {
            @Override
            public void onRunning(String command, String newLine) {
                String current = output.get();
                output.set(current + newLine + "\n");
            }

            @Override
            public void onFinished(String command, String log, int status) {
                if (!log.isEmpty()) output.set(log);
            }

            @Override
            public void onError(String command, Exception exception) {
                output.set("");
            }
        });
        return output.get().trim();
    }
}
