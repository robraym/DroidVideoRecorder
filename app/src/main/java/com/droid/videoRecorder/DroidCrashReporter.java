package com.droid.videoRecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.preference.PreferenceManager;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DroidCrashReporter {
    private static final String KEY_LAST_CRASH = "diagnostico_ultimo_erro";
    private static final String KEY_CRASH_PENDING = "diagnostico_erro_pendente";
    private static boolean initialized;

    private DroidCrashReporter() {
    }

    public static synchronized void Initialize(Context context) {
        if (initialized || context == null) {
            return;
        }

        initialized = true;
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            SaveCrash(appContext, thread, throwable);
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        });
    }

    public static boolean HasPendingCrash(Context context) {
        return GetPreferences(context).getBoolean(KEY_CRASH_PENDING, false);
    }

    public static String GetLastCrash(Context context) {
        return GetPreferences(context).getString(KEY_LAST_CRASH, "");
    }

    public static void ClearLastCrash(Context context) {
        GetPreferences(context)
                .edit()
                .remove(KEY_LAST_CRASH)
                .putBoolean(KEY_CRASH_PENDING, false)
                .apply();
    }

    private static void SaveCrash(Context context, Thread thread, Throwable throwable) {
        if (context == null || throwable == null) {
            return;
        }

        try {
            StringWriter stackWriter = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stackWriter));

            StringBuilder report = new StringBuilder();
            report.append("Recorder crash report").append('\n');
            report.append("Time: ").append(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US).format(new Date())).append('\n');
            report.append("Device: ")
                    .append(Build.MANUFACTURER)
                    .append(' ')
                    .append(Build.MODEL)
                    .append('\n');
            report.append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n');
            report.append("Android release: ").append(Build.VERSION.RELEASE).append('\n');
            report.append("App version: ").append(GetAppVersion(context)).append('\n');
            report.append("Thread: ")
                    .append(thread != null ? thread.getName() : "unknown")
                    .append('\n');
            report.append('\n');
            report.append(stackWriter);

            GetPreferences(context)
                    .edit()
                    .putString(KEY_LAST_CRASH, report.toString())
                    .putBoolean(KEY_CRASH_PENDING, true)
                    .commit();
        } catch (Exception ignored) {
        }
    }

    private static String GetAppVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static SharedPreferences GetPreferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }
}
