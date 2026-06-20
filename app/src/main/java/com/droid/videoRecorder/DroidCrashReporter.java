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
    private static final String KEY_DIAGNOSTIC_TRAIL = "diagnostico_ultimos_passos";
    private static final int MAX_TRAIL_LINES = 35;
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

    public static void LogEvent(Context context, String event) {
        if (context == null || event == null || event.length() == 0) {
            return;
        }

        try {
            SharedPreferences preferences = GetPreferences(context);
            String existingTrail = preferences.getString(KEY_DIAGNOSTIC_TRAIL, "");
            String eventLine = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US).format(new Date()) + " - " + event;

            String[] existingLines = existingTrail.length() > 0
                    ? existingTrail.split("\\n")
                    : new String[0];
            int firstLine = Math.max(0, existingLines.length - MAX_TRAIL_LINES + 1);
            StringBuilder trail = new StringBuilder();
            for (int i = firstLine; i < existingLines.length; i++) {
                if (existingLines[i].length() == 0) {
                    continue;
                }
                if (trail.length() > 0) {
                    trail.append('\n');
                }
                trail.append(existingLines[i]);
            }
            if (trail.length() > 0) {
                trail.append('\n');
            }
            trail.append(eventLine);

            preferences.edit()
                    .putString(KEY_DIAGNOSTIC_TRAIL, trail.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static void SaveDiagnostic(Context context, String title, Throwable throwable) {
        if (context == null || title == null || title.length() == 0) {
            return;
        }

        try {
            StringBuilder report = new StringBuilder();
            report.append("Recorder diagnostic report").append('\n');
            report.append("Reason: ").append(title).append('\n');
            AppendDeviceInfo(context, report);
            if (throwable != null) {
                StringWriter stackWriter = new StringWriter();
                throwable.printStackTrace(new PrintWriter(stackWriter));
                report.append('\n').append(stackWriter);
            }
            AppendTrail(context, report);

            GetPreferences(context)
                    .edit()
                    .putString(KEY_LAST_CRASH, report.toString())
                    .putBoolean(KEY_CRASH_PENDING, true)
                    .commit();
        } catch (Exception ignored) {
        }
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
            AppendDeviceInfo(context, report);
            report.append("Thread: ")
                    .append(thread != null ? thread.getName() : "unknown")
                    .append('\n');
            report.append('\n');
            report.append(stackWriter);
            AppendTrail(context, report);

            GetPreferences(context)
                    .edit()
                    .putString(KEY_LAST_CRASH, report.toString())
                    .putBoolean(KEY_CRASH_PENDING, true)
                    .commit();
        } catch (Exception ignored) {
        }
    }

    private static void AppendDeviceInfo(Context context, StringBuilder report) {
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
    }

    private static void AppendTrail(Context context, StringBuilder report) {
        String trail = GetPreferences(context).getString(KEY_DIAGNOSTIC_TRAIL, "");
        if (trail == null || trail.length() == 0) {
            return;
        }

        report.append('\n')
                .append("Recent events")
                .append('\n')
                .append(trail)
                .append('\n');
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
