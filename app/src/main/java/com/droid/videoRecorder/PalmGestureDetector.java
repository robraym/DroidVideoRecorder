package com.droid.videoRecorder;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class PalmGestureDetector {
    interface Listener {
        void onOpenPalmDetected();
    }

    private static final String MODEL_ASSET = "gesture_recognizer.task";
    private static final int ANALYSIS_IMAGE_SIZE = 192;
    private static final long ANALYSIS_INTERVAL_MS = 110;
    private static final float MIN_OPEN_PALM_SCORE = 0.32f;
    private static final float INSTANT_OPEN_PALM_SCORE = 0.55f;
    private static final int REQUIRED_CONSECUTIVE_DETECTIONS = 2;
    private static final int MIN_SUPPORTED_SDK = Build.VERSION_CODES.P;

    private final Context context;
    private final TextureView previewView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private GestureRecognizer gestureRecognizer;
    private boolean active;
    private volatile boolean analyzing;
    private volatile boolean runtimeSupported = true;
    private boolean unsupportedSdkLogged;
    private int consecutiveDetections;
    private String lastLoggedGesture = "";

    PalmGestureDetector(Context context, TextureView previewView, Listener listener) {
        this.context = context.getApplicationContext();
        this.previewView = previewView;
        this.listener = listener;
    }

    private final Runnable analyzeFrame = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }

            if (!analyzing && previewView.isAvailable()) {
                Bitmap bitmap = previewView.getBitmap(ANALYSIS_IMAGE_SIZE, ANALYSIS_IMAGE_SIZE);
                if (bitmap != null) {
                    analyzing = true;
                    executor.execute(() -> Analyze(bitmap));
                }
            }

            mainHandler.postDelayed(this, ANALYSIS_INTERVAL_MS);
        }
    };

    void Start() {
        if (!IsSupported()) {
            LogUnsupportedSdkOnce();
            return;
        }
        if (active) {
            return;
        }
        active = true;
        consecutiveDetections = 0;
        executor.execute(this::EnsureGestureRecognizer);
        mainHandler.post(analyzeFrame);
    }

    void Pause() {
        active = false;
        consecutiveDetections = 0;
        mainHandler.removeCallbacks(analyzeFrame);
    }

    void Close() {
        Pause();
        executor.execute(() -> {
            if (gestureRecognizer != null) {
                gestureRecognizer.close();
                gestureRecognizer = null;
            }
        });
        executor.shutdown();
    }

    private void Analyze(Bitmap bitmap) {
        MPImage image = null;
        try {
            EnsureGestureRecognizer();
            image = new BitmapImageBuilder(bitmap).build();
            GestureRecognizerResult result = gestureRecognizer.recognize(image);
            float openPalmScore = GetOpenPalmScore(result.gestures());
            mainHandler.post(() -> HandleResult(openPalmScore));
        } catch (Throwable ex) {
            runtimeSupported = false;
            Log.e("DVR", "Falha ao reconhecer gesto de palma", ex);
            Pause();
        } finally {
            if (image != null) {
                image.close();
            }
            bitmap.recycle();
            analyzing = false;
        }
    }

    private void EnsureGestureRecognizer() {
        if (gestureRecognizer != null) {
            return;
        }

        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build();
        GestureRecognizer.GestureRecognizerOptions options =
                GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMinHandDetectionConfidence(0.25f)
                        .setMinHandPresenceConfidence(0.25f)
                        .build();
        gestureRecognizer = GestureRecognizer.createFromOptions(context, options);
        Log.d("DVR-PALM", "Detector de palma inicializado");
    }

    private boolean IsSupported() {
        return runtimeSupported && Build.VERSION.SDK_INT >= MIN_SUPPORTED_SDK;
    }

    private void LogUnsupportedSdkOnce() {
        if (unsupportedSdkLogged) {
            return;
        }
        unsupportedSdkLogged = true;
        Log.d("DVR-PALM", "Gesto de palma desativado neste Android por compatibilidade");
    }

    private float GetOpenPalmScore(List<List<Category>> gestures) {
        Category bestGesture = null;
        float openPalmScore = 0f;
        for (List<Category> handGestures : gestures) {
            for (Category gesture : handGestures) {
                if (bestGesture == null || gesture.score() > bestGesture.score()) {
                    bestGesture = gesture;
                }
                if ("Open_Palm".equals(gesture.categoryName())) {
                    openPalmScore = Math.max(openPalmScore, gesture.score());
                }
            }
        }
        if (openPalmScore >= MIN_OPEN_PALM_SCORE) {
            Log.d("DVR-PALM", "Palma aberta reconhecida: " + openPalmScore);
            return openPalmScore;
        }
        if (bestGesture != null && !bestGesture.categoryName().equals(lastLoggedGesture)) {
            lastLoggedGesture = bestGesture.categoryName();
            Log.d("DVR-PALM", "Melhor gesto observado: "
                    + bestGesture.categoryName()
                    + " (" + bestGesture.score() + ")");
        }
        return 0f;
    }

    private void HandleResult(float openPalmScore) {
        if (!active) {
            return;
        }

        boolean openPalmDetected = openPalmScore >= MIN_OPEN_PALM_SCORE;
        consecutiveDetections = openPalmDetected ? consecutiveDetections + 1 : 0;
        if (openPalmDetected) {
            Log.d("DVR-PALM", "Confirmacao de palma: " + consecutiveDetections);
        }
        if (openPalmScore < INSTANT_OPEN_PALM_SCORE
                && consecutiveDetections < REQUIRED_CONSECUTIVE_DETECTIONS) {
            return;
        }

        Pause();
        listener.onOpenPalmDetected();
    }
}
