package com.droid.videoRecorder;

import android.app.Application;

public class DroidRecorderApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DroidCrashReporter.Initialize(this);
    }
}
