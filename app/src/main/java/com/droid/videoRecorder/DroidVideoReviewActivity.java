package com.droid.videoRecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.animation.Animator;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Collections;

public class DroidVideoReviewActivity extends Activity {
    private static final String EXTRA_VIDEO_URI = "extra_video_uri";
    private static final String EXTRA_VIDEO_PATH = "extra_video_path";
    private static final String EXTRA_DISPLAY_NAME = "extra_display_name";
    private static final String EXTRA_REVEAL_CENTER_X = "extra_reveal_center_x";
    private static final String EXTRA_REVEAL_CENTER_Y = "extra_reveal_center_y";
    private static final String EXTRA_REVEAL_RADIUS = "extra_reveal_radius";
    private static final int REQUEST_TRASH_VIDEO = 7310;

    private Uri videoUri;
    private String videoPath;
    private String displayName;
    private Uri originalVideoUri;
    private String originalVideoPath;
    private String originalDisplayName;
    private Uri enhancedVideoUri;
    private String enhancedVideoPath;
    private FrameLayout rootView;
    private FrameLayout videoContainer;
    private FrameLayout enhancementOverlay;
    private PlayerView videoView;
    private ExoPlayer mediaPlayer;
    private ImageButton playButton;
    private TextView playLabel;
    private TextView closeButton;
    private LinearLayout statusPanel;
    private TextView statusTitleLabel;
    private TextView statusDetailLabel;
    private LinearLayout controlsPanel;
    private View restoreControl;
    private SeekBar progressBar;
    private TextView currentTimeLabel;
    private TextView durationLabel;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private boolean userSeeking;
    private boolean playing;
    private boolean deleted;
    private boolean enhancingVideo;
    private boolean processingAudioMode;
    private int processingProgressPercent = -1;
    private int processingEstimatedSeconds = -1;
    private int processingAnimationStep;
    private int processingTitleRes = R.string.revisao_video_melhorando;
    private int processingSummaryRes = R.string.revisao_video_melhorando_resumo;
    private int revealCenterX;
    private int revealCenterY;
    private int revealRadius;
    private boolean recorderRestoreRequested;
    private boolean emergencyClosing;
    private volatile boolean reviewWatchdogRunning;
    private volatile boolean reviewWatchdogReported;
    private volatile long lastReviewWatchdogBeat;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            UpdateProgress();
            progressHandler.postDelayed(this, 250);
        }
    };

    private final Runnable processingAnimator = new Runnable() {
        @Override
        public void run() {
            if (!enhancingVideo) {
                return;
            }
            processingAnimationStep = (processingAnimationStep + 1) % 4;
            UpdateProcessingOverlayText();
            progressHandler.postDelayed(this, 450);
        }
    };

    public static Intent CreateIntent(Context context, DroidVideoRecorder.RecordedVideo video) {
        return CreateIntent(context, video, 0, 0, 0);
    }

    public static Intent CreateIntent(Context context, DroidVideoRecorder.RecordedVideo video,
                                      int revealCenterX, int revealCenterY, int revealRadius) {
        Intent intent = new Intent(context, DroidVideoReviewActivity.class);
        if (video.uri != null) {
            intent.putExtra(EXTRA_VIDEO_URI, video.uri.toString());
        }
        intent.putExtra(EXTRA_VIDEO_PATH, video.legacyPath);
        intent.putExtra(EXTRA_DISPLAY_NAME, video.displayName);
        intent.putExtra(EXTRA_REVEAL_CENTER_X, revealCenterX);
        intent.putExtra(EXTRA_REVEAL_CENTER_Y, revealCenterY);
        intent.putExtra(EXTRA_REVEAL_RADIUS, revealRadius);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        ConfigureWindow();
        ReadIntent();

        if (videoUri == null && videoPath == null) {
            Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LogReviewEvent("review create");
        StartReviewWatchdog();
        try {
            LogReviewEvent("stop recorder for review start");
            DroidHeadService.StopForVideoReview();
            LogReviewEvent("stop recorder for review end");
        } catch (Exception ex) {
            LogReviewException("stop recorder for review failed", ex);
        }
        BuildLayout();
        StartVideo();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playing = false;
            UpdatePlayState();
        }
    }

    @Override
    protected void onDestroy() {
        LogReviewEvent("review destroy start");
        if (!emergencyClosing) {
            RequestRecorderRestore("review destroy");
        }
        ReleasePlayer("review destroy");
        StopReviewWatchdog();
        LogReviewEvent("review destroy end");
        super.onDestroy();
    }

    private void RequestRecorderRestore(String reason) {
        if (recorderRestoreRequested) {
            return;
        }

        recorderRestoreRequested = true;
        LogReviewEvent("restore recorder requested: " + reason);
        try {
            Intent intentService = new Intent(getApplicationContext(), DroidHeadService.class);
            ContextCompat.startForegroundService(getApplicationContext(), intentService);
            LogReviewEvent("restore recorder started");
        } catch (Exception ex) {
            LogReviewException("restore recorder failed", ex);
            RequestEmergencyClose("Falha ao restaurar a bolinha depois da revisao", ex);
        }
    }

    private void StartReviewWatchdog() {
        reviewWatchdogRunning = true;
        reviewWatchdogReported = false;
        lastReviewWatchdogBeat = System.currentTimeMillis();
        Thread watchdogThread = new Thread(() -> {
            while (reviewWatchdogRunning) {
                try {
                    progressHandler.post(() -> lastReviewWatchdogBeat = System.currentTimeMillis());
                } catch (Exception ignored) {
                }

                SleepWatchdog(2000);

                long stalledFor = System.currentTimeMillis() - lastReviewWatchdogBeat;
                if (reviewWatchdogRunning && !reviewWatchdogReported && stalledFor > 7000) {
                    reviewWatchdogReported = true;
                    DroidCrashReporter.LogEvent(
                            getApplicationContext(),
                            "Review: main thread stalled for " + stalledFor + "ms");
                    DroidCrashReporter.SaveDiagnostic(
                            getApplicationContext(),
                            "Tela de revisao ficou sem resposta por " + stalledFor + "ms",
                            null);
                    ForceKillProcess();
                }
            }
        }, "DVR-ReviewWatchdog");
        watchdogThread.start();
    }

    private void StopReviewWatchdog() {
        reviewWatchdogRunning = false;
    }

    private void SleepWatchdog(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private void LogReviewEvent(String event) {
        Log.d("DVR-Review", event);
        DroidCrashReporter.LogEvent(getApplicationContext(), "Review: " + event);
    }

    private void LogReviewException(String event, Exception ex) {
        String message = ex != null && ex.getMessage() != null ? ex.getMessage() : String.valueOf(ex);
        Log.e("DVR-Review", event + ": " + message, ex);
        DroidCrashReporter.LogEvent(getApplicationContext(), "Review: " + event + ": " + message);
    }

    private void RequestEmergencyClose(String reason, Throwable throwable) {
        emergencyClosing = true;
        LogReviewEvent("emergency close requested: " + reason);
        DroidCrashReporter.SaveDiagnostic(getApplicationContext(), reason, throwable);
        StopReviewWatchdog();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        } catch (Exception ex) {
            LogReviewException("finish emergency close failed", ex);
        }

        progressHandler.postDelayed(this::ForceKillProcess, 450);
    }

    private void ForceKillProcess() {
        LogReviewEvent("force kill process");
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TRASH_VIDEO) {
            return;
        }

        if (resultCode == RESULT_OK) {
            deleted = true;
            LogReviewEvent("trash request accepted");
            RequestRecorderRestore("trash result ok");
            Toast.makeText(this, getString(R.string.revisao_video_movido_lixeira), Toast.LENGTH_SHORT).show();
            finish();
        } else {
            LogReviewEvent("trash request canceled");
            StartVideo();
        }
    }

    private void ConfigureWindow() {
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void ReadIntent() {
        Intent intent = getIntent();
        String uriText = intent.getStringExtra(EXTRA_VIDEO_URI);
        videoUri = uriText != null ? Uri.parse(uriText) : null;
        videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH);
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        originalVideoUri = videoUri;
        originalVideoPath = videoPath;
        originalDisplayName = displayName;
        revealCenterX = intent.getIntExtra(EXTRA_REVEAL_CENTER_X, 0);
        revealCenterY = intent.getIntExtra(EXTRA_REVEAL_CENTER_Y, 0);
        revealRadius = intent.getIntExtra(EXTRA_REVEAL_RADIUS, 0);
    }

    private void BuildLayout() {
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(Color.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(10), dp(70), dp(8));

        TextView headerTitle = new TextView(this);
        headerTitle.setText(getString(R.string.revisao_video_titulo));
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(18);
        headerTitle.setSingleLine(true);
        header.addView(headerTitle);

        TextView headerSubtitle = new TextView(this);
        headerSubtitle.setText(getString(R.string.revisao_video_subtitulo));
        headerSubtitle.setTextColor(Color.rgb(170, 174, 184));
        headerSubtitle.setTextSize(12);
        headerSubtitle.setSingleLine(true);
        headerSubtitle.setIncludeFontPadding(true);
        headerSubtitle.setPadding(0, 0, 0, dp(2));
        header.addView(headerSubtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22)));

        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(64),
                Gravity.TOP);
        headerParams.setMargins(0, dp(8), 0, 0);
        rootView.addView(header, headerParams);

        FrameLayout mediaPanel = new FrameLayout(this);
        mediaPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        mediaPanel.setBackground(CreateRounded(Color.rgb(18, 19, 23), dp(24)));
        FrameLayout.LayoutParams mediaPanelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        mediaPanelParams.setMargins(dp(16), dp(80), dp(16), dp(176));
        rootView.addView(mediaPanel, mediaPanelParams);

        videoContainer = new FrameLayout(this);
        videoContainer.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams videoContainerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        mediaPanel.addView(videoContainer, videoContainerParams);

        videoView = new PlayerView(this);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setUseController(false);
        videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoView.setShutterBackgroundColor(Color.BLACK);
        videoContainer.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        closeButton = new TextView(this);
        closeButton.setText("X");
        closeButton.setTextColor(Color.WHITE);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setTypeface(Typeface.DEFAULT_BOLD);
        closeButton.setTextSize(16);
        closeButton.setBackground(CreateRounded(Color.rgb(54, 56, 64), dp(21)));
        closeButton.setOnClickListener(v -> {
            if (!enhancingVideo) {
                LogReviewEvent("close button clicked");
                ShowCloseConfirmation();
            }
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(42), dp(42),
                Gravity.TOP | Gravity.RIGHT);
        closeParams.setMargins(0, dp(28), dp(18), 0);
        rootView.addView(closeButton, closeParams);

        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(dp(18), dp(9), dp(18), dp(9));
        statusPanel.setBackground(CreateRounded(Color.argb(150, 28, 29, 33), dp(18)));

        statusTitleLabel = new TextView(this);
        statusTitleLabel.setTextColor(Color.rgb(232, 233, 238));
        statusTitleLabel.setTextSize(14);
        statusTitleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitleLabel.setGravity(Gravity.CENTER);
        statusTitleLabel.setSingleLine(true);
        statusPanel.addView(statusTitleLabel);

        statusDetailLabel = new TextView(this);
        statusDetailLabel.setTextColor(Color.rgb(190, 193, 201));
        statusDetailLabel.setTextSize(12);
        statusDetailLabel.setGravity(Gravity.CENTER);
        statusDetailLabel.setSingleLine(true);
        statusDetailLabel.setPadding(0, dp(2), 0, 0);
        statusPanel.addView(statusDetailLabel);
        statusPanel.setVisibility(View.GONE);

        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        statusParams.setMargins(dp(8), 0, dp(8), dp(8));
        mediaPanel.addView(statusPanel, statusParams);

        LinearLayout progressPanel = CreateProgressPanel();
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        progressParams.setMargins(dp(16), 0, dp(16), dp(122));
        rootView.addView(progressPanel, progressParams);

        LinearLayout controls = new LinearLayout(this);
        controlsPanel = controls;
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(12), dp(10), dp(12), dp(10));
        controls.setBackground(CreateRounded(Color.rgb(28, 29, 33), dp(28)));

        LinearLayout.LayoutParams controlItemParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        controls.addView(CreatePlayControl(), controlItemParams);
        controls.addView(CreateControl(android.R.drawable.ic_menu_share,
                getString(R.string.revisao_video_compartilhar),
                Color.rgb(48, 118, 230),
                v -> ShareVideo()), controlItemParams);
        if (DroidVideoRecorder.IsAudioNoiseReductionSupported()) {
            controls.addView(CreateControl(R.drawable.ic_ai_sparkles_bitmap,
                    getString(R.string.revisao_video_voz),
                    Color.rgb(96, 108, 205),
                    v -> ReduceAudioNoise()), controlItemParams);
            restoreControl = CreateControl(android.R.drawable.ic_menu_revert,
                    getString(R.string.revisao_video_restaurar),
                    Color.rgb(86, 88, 96),
                    v -> RestoreOriginalVideo());
            restoreControl.setVisibility(View.GONE);
            controls.addView(restoreControl, controlItemParams);
        }
        controls.addView(CreateControl(android.R.drawable.ic_menu_delete,
                getString(R.string.revisao_video_apagar),
                Color.rgb(232, 65, 72),
                v -> ShowDeleteConfirmation()), controlItemParams);

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        controlsParams.setMargins(dp(16), 0, dp(16), dp(24));
        rootView.addView(controls, controlsParams);

        rootView.setVisibility(View.INVISIBLE);
        setContentView(rootView);
        RunOpeningReveal();
    }

    private void RunOpeningReveal() {
        if (rootView == null) {
            return;
        }

        rootView.post(() -> {
            if (revealRadius <= 0 || revealCenterX <= 0 || revealCenterY <= 0) {
                rootView.setVisibility(View.VISIBLE);
                return;
            }

            int[] rootLocation = new int[2];
            rootView.getLocationOnScreen(rootLocation);
            int centerX = revealCenterX - rootLocation[0];
            int centerY = revealCenterY - rootLocation[1];
            if (centerX < 0 || centerY < 0 || centerX > rootView.getWidth() || centerY > rootView.getHeight()) {
                centerX = rootView.getWidth() / 2;
                centerY = rootView.getHeight() / 2;
            }

            float endRadius = (float) Math.hypot(
                    Math.max(centerX, rootView.getWidth() - centerX),
                    Math.max(centerY, rootView.getHeight() - centerY));
            Animator reveal = ViewAnimationUtils.createCircularReveal(
                    rootView,
                    centerX,
                    centerY,
                    Math.max(1, revealRadius),
                    endRadius);
            reveal.setDuration(430);
            reveal.setInterpolator(new AccelerateDecelerateInterpolator());
            rootView.setVisibility(View.VISIBLE);
            reveal.start();
        });
    }

    private LinearLayout CreateProgressPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(14), dp(8), dp(14), dp(8));
        panel.setBackground(CreateRounded(Color.rgb(22, 23, 27), dp(22)));

        currentTimeLabel = CreateTimeLabel("00:00");
        panel.addView(currentTimeLabel);

        progressBar = new SeekBar(this);
        progressBar.setMax(0);
        progressBar.setProgress(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(28, 145, 96)));
            progressBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(80, 82, 90)));
        }
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTimeLabel.setText(FormatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mediaPlayer != null) {
                    mediaPlayer.seekTo(seekBar.getProgress());
                }
                userSeeking = false;
                UpdateProgress();
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1);
        seekParams.setMargins(dp(8), 0, dp(8), 0);
        panel.addView(progressBar, seekParams);

        durationLabel = CreateTimeLabel("00:00");
        panel.addView(durationLabel);

        return panel;
    }

    private TextView CreateTimeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(232, 233, 238));
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setMinWidth(dp(42));
        return label;
    }

    private View CreatePlayControl() {
        LinearLayout container = CreateControlContainer();
        playButton = CreateIconButton(android.R.drawable.ic_media_pause, Color.rgb(28, 145, 96));
        playButton.setOnClickListener(v -> TogglePlay());
        container.addView(playButton);
        playLabel = CreateControlLabel(getString(R.string.revisao_video_pause));
        container.addView(playLabel);
        return container;
    }

    private View CreateControl(int icon, String label, int color, View.OnClickListener listener) {
        LinearLayout container = CreateControlContainer();
        ImageButton button = CreateIconButton(icon, color);
        button.setOnClickListener(listener);
        container.addView(button);
        container.addView(CreateControlLabel(label));
        return container;
    }

    private LinearLayout CreateControlContainer() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(2), 0, dp(2), 0);
        return container;
    }

    private ImageButton CreateIconButton(int icon, int color) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        boolean aiIcon = icon == R.drawable.ic_ai_sparkles_bitmap;
        if (!aiIcon) {
            button.setColorFilter(Color.WHITE);
        }
        button.setBackground(CreateRounded(color, dp(22)));
        if (aiIcon) {
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setPadding(dp(5), dp(5), dp(3), dp(5));
        } else {
            int padding = dp(10);
            button.setPadding(padding, padding, padding, padding);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        button.setLayoutParams(params);
        return button;
    }

    private TextView CreateControlLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(232, 233, 238));
        label.setTextSize(8);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setPadding(0, dp(6), 0, 0);
        return label;
    }

    private GradientDrawable CreateRounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void StartVideo() {
        LogReviewEvent("start video");
        PrepareVideo();
    }

    private void PrepareVideo() {
        try {
            ReleasePlayer("prepare video");
            LogReviewEvent("player build start");
            mediaPlayer = new ExoPlayer.Builder(this).build();
            videoView.setPlayer(mediaPlayer);
            Uri sourceUri = videoUri != null ? videoUri : Uri.fromFile(new File(videoPath));
            mediaPlayer.setMediaItem(MediaItem.fromUri(sourceUri));
            mediaPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        UpdateDuration();
                        playing = mediaPlayer != null && mediaPlayer.isPlaying();
                        UpdatePlayState();
                        StartProgressUpdates();
                    } else if (playbackState == Player.STATE_ENDED) {
                        playing = false;
                        if (progressBar != null) {
                            progressBar.setProgress(progressBar.getMax());
                        }
                        if (currentTimeLabel != null && mediaPlayer != null) {
                            currentTimeLabel.setText(FormatTime(GetSafeDuration()));
                        }
                        UpdatePlayState();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    playing = isPlaying;
                    UpdatePlayState();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    LogReviewException("player error", error);
                    Toast.makeText(DroidVideoReviewActivity.this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
                    playing = false;
                    UpdatePlayState();
                    RequestEmergencyClose("Erro no player da revisao", error);
                }
            });
            mediaPlayer.prepare();
            mediaPlayer.play();
            LogReviewEvent("player build end");
        } catch (Exception ex) {
            LogReviewException("prepare video failed", ex);
            Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
            playing = false;
            UpdatePlayState();
            RequestEmergencyClose("Falha ao abrir o video na revisao", ex);
        }
    }

    private void ReleasePlayer(String reason) {
        LogReviewEvent("release player start: " + reason);
        StopProgressUpdates();
        ExoPlayer player = mediaPlayer;
        mediaPlayer = null;
        playing = false;
        if (player != null) {
            try {
                if (videoView != null) {
                    videoView.setPlayer(null);
                }
            } catch (Exception ex) {
                LogReviewException("detach player failed", ex);
            }

            try {
                player.release();
            } catch (Exception ex) {
                LogReviewException("release player failed", ex);
                RequestEmergencyClose("Falha ao liberar o player da revisao", ex);
            }
        }
        LogReviewEvent("release player end: " + reason);
    }

    private void PausePlayer(String reason) {
        if (mediaPlayer == null) {
            return;
        }

        try {
            LogReviewEvent("pause player: " + reason);
            mediaPlayer.pause();
            playing = false;
            UpdatePlayState();
        } catch (Exception ex) {
            LogReviewException("pause player failed", ex);
        }
    }

    private void TogglePlay() {
        if (mediaPlayer == null || deleted || enhancingVideo) {
            return;
        }

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playing = false;
        } else {
            if (ShouldReplayFromBeginning()) {
                mediaPlayer.seekTo(0);
                if (progressBar != null) {
                    progressBar.setProgress(0);
                }
                if (currentTimeLabel != null) {
                    currentTimeLabel.setText(FormatTime(0));
                }
            }
            mediaPlayer.play();
            playing = true;
        }
        UpdatePlayState();
    }

    private boolean ShouldReplayFromBeginning() {
        if (mediaPlayer == null) {
            return false;
        }

        if (mediaPlayer.getPlaybackState() == Player.STATE_ENDED) {
            return true;
        }

        int duration = GetSafeDuration();
        if (duration <= 0) {
            return false;
        }

        return mediaPlayer.getCurrentPosition() >= duration - 250;
    }

    private void UpdatePlayState() {
        if (playButton == null || playLabel == null) {
            return;
        }
        playButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playLabel.setText(playing ? getString(R.string.revisao_video_pause) : getString(R.string.revisao_video_play));
    }

    private void StartProgressUpdates() {
        progressHandler.removeCallbacks(progressUpdater);
        progressHandler.post(progressUpdater);
    }

    private void StopProgressUpdates() {
        progressHandler.removeCallbacks(progressUpdater);
    }

    private void UpdateDuration() {
        if (mediaPlayer == null || progressBar == null || durationLabel == null) {
            return;
        }

        int duration = GetSafeDuration();
        progressBar.setMax(duration);
        durationLabel.setText(FormatTime(duration));
        UpdateProgress();
    }

    private void UpdateProgress() {
        if (mediaPlayer == null || progressBar == null || currentTimeLabel == null || userSeeking) {
            return;
        }

        try {
            int position = (int) Math.max(0, Math.min(Integer.MAX_VALUE, mediaPlayer.getCurrentPosition()));
            progressBar.setProgress(position);
            currentTimeLabel.setText(FormatTime(position));
        } catch (Exception ignored) {
        }
    }

    private int GetSafeDuration() {
        if (mediaPlayer == null || mediaPlayer.getDuration() == C.TIME_UNSET) {
            return 0;
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, mediaPlayer.getDuration()));
    }

    private String FormatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void ShowDeleteConfirmation() {
        if (enhancingVideo) {
            return;
        }
        if (deleted) {
            finish();
            return;
        }

        ShowReviewConfirmation(
                R.string.revisao_video_confirmar_apagar_titulo,
                R.string.revisao_video_confirmar_apagar_mensagem,
                R.string.revisao_video_mover_lixeira,
                "delete confirmation accepted",
                this::MoveVideoToTrash);
    }

    private void ShowCloseConfirmation() {
        if (enhancingVideo) {
            return;
        }

        ShowReviewConfirmation(
                R.string.revisao_video_confirmar_fechar_titulo,
                R.string.revisao_video_confirmar_fechar_mensagem,
                R.string.revisao_video_fechar,
                "close confirmation accepted",
                this::finish);
    }

    private void ShowReviewConfirmation(int titleRes, int messageRes, int actionRes,
                                        String actionEvent, Runnable action) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(18));
        content.setBackground(CreateRounded(Color.rgb(28, 29, 33), dp(26)));

        TextView title = new TextView(this);
        title.setText(getString(titleRes));
        title.setTextColor(Color.rgb(244, 246, 250));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.LEFT);
        content.addView(title);

        TextView message = new TextView(this);
        message.setText(getString(messageRes));
        message.setTextColor(Color.rgb(166, 171, 183));
        message.setTextSize(13);
        message.setGravity(Gravity.LEFT);
        message.setLineSpacing(dp(2), 1f);
        message.setPadding(0, dp(8), 0, 0);
        content.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.RIGHT);
        actions.setPadding(0, dp(20), 0, 0);

        TextView cancel = CreateDialogButton(getString(R.string.revisao_video_cancelar),
                Color.rgb(48, 50, 58), Color.rgb(226, 229, 236));
        TextView confirm = CreateDialogButton(getString(actionRes),
                Color.rgb(45, 108, 223), Color.WHITE);

        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        cancelParams.setMargins(0, 0, dp(10), 0);
        actions.addView(cancel, cancelParams);
        actions.addView(confirm, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        content.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            LogReviewEvent(actionEvent);
            action.run();
        });
        dialog.setOnShowListener(dialogInterface -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        dialog.show();
    }

    private TextView CreateDialogButton(String text, int backgroundColor, int textColor) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(104));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(CreateRounded(backgroundColor, dp(22)));
        return button;
    }

    private void MoveVideoToTrash() {
        if (enhancingVideo) {
            return;
        }
        if (deleted) {
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && videoUri != null) {
            try {
                PausePlayer("move to trash");
                if (MoveMediaStoreVideoToTrashDirectly()) {
                    deleted = true;
                    RequestRecorderRestore("trash moved directly");
                    Toast.makeText(this, getString(R.string.revisao_video_movido_lixeira), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                PendingIntent request = MediaStore.createTrashRequest(
                        getContentResolver(),
                        Collections.singletonList(videoUri),
                        true);
                startIntentSenderForResult(
                        request.getIntentSender(),
                        REQUEST_TRASH_VIDEO,
                        null,
                        0,
                        0,
                        0);
                return;
            } catch (IntentSender.SendIntentException ex) {
                LogReviewException("trash request failed", ex);
                StartVideo();
                Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
                return;
            } catch (Exception ex) {
                LogReviewException("move to trash failed", ex);
            }
        }

        DeleteVideoPermanently();
    }

    private boolean MoveMediaStoreVideoToTrashDirectly() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || videoUri == null) {
            return false;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_TRASHED, 1);
            return getContentResolver().update(videoUri, values, null, null) > 0;
        } catch (Exception ex) {
            LogReviewException("direct trash failed", ex);
            return false;
        }
    }

    private void DeleteVideoPermanently() {
        if (deleted) {
            finish();
            return;
        }

        boolean success = false;
        try {
            ReleasePlayer("delete permanently");
            if (videoUri != null) {
                success = getContentResolver().delete(videoUri, null, null) > 0;
            } else if (videoPath != null) {
                success = new File(videoPath).delete();
            }
        } catch (Exception ignored) {
        }

        if (success) {
            deleted = true;
            Toast.makeText(this, getString(R.string.revisao_video_movido_lixeira), Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
        }
    }

    private void ShareVideo() {
        if (enhancingVideo) {
            return;
        }
        Uri shareUri = GetShareUri();
        if (shareUri == null) {
            Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("video/mp4");
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.revisao_video_compartilhar_com)));
    }

    private void EnhanceVideo() {
        ProcessVideoEnhancement(false);
    }

    private void ReduceAudioNoise() {
        ProcessVideoEnhancement(true);
    }

    private void ProcessVideoEnhancement(boolean audioMode) {
        if (enhancingVideo || deleted) {
            return;
        }

        DroidVideoRecorder.RecordedVideo video = new DroidVideoRecorder.RecordedVideo(
                videoUri,
                videoPath,
                displayName);
        if (!video.HasVideo()) {
            Toast.makeText(this, getString(audioMode
                    ? R.string.revisao_video_voz_erro
                    : R.string.revisao_video_melhorar_erro), Toast.LENGTH_SHORT).show();
            return;
        }

        processingTitleRes = audioMode ? R.string.revisao_video_voz_processando : R.string.revisao_video_melhorando;
        processingSummaryRes = audioMode
                ? R.string.revisao_video_voz_processando_resumo
                : R.string.revisao_video_melhorando_resumo;
        processingAudioMode = audioMode;
        processingProgressPercent = 1;
        processingEstimatedSeconds = -1;
        if (audioMode) {
            PausePlayerForProcessing();
        } else {
            ReleasePlayer("enhance video");
        }
        SetEnhancementState(true);
        DroidVideoRecorder.VideoEnhancementListener listener = new DroidVideoRecorder.VideoEnhancementListener() {
            @Override
            public void OnVideoEnhanced(DroidVideoRecorder.RecordedVideo video) {
                runOnUiThread(() -> {
                    DeleteEnhancedVideoIfPossible();
                    videoUri = video.uri;
                    videoPath = video.legacyPath;
                    displayName = video.displayName;
                    enhancedVideoUri = video.uri;
                    enhancedVideoPath = video.legacyPath;
                    if (restoreControl != null) {
                        restoreControl.setVisibility(View.VISIBLE);
                    }
                    SetEnhancementState(false);
                    Toast.makeText(DroidVideoReviewActivity.this,
                            getString(audioMode
                                    ? R.string.revisao_video_voz_aplicada
                                    : R.string.revisao_video_melhorado),
                            Toast.LENGTH_SHORT).show();
                    StartVideo();
                });
            }

            @Override
            public void OnVideoEnhancementFailed() {
                runOnUiThread(() -> {
                    SetEnhancementState(false);
                    Toast.makeText(DroidVideoReviewActivity.this,
                            getString(audioMode
                                    ? R.string.revisao_video_voz_erro
                                    : R.string.revisao_video_melhorar_erro),
                            Toast.LENGTH_SHORT).show();
                    StartVideo();
                });
            }

            @Override
            public void OnVideoEnhancementProgress(int percent, int estimatedSecondsRemaining) {
                runOnUiThread(() -> {
                    processingProgressPercent = percent;
                    processingEstimatedSeconds = estimatedSecondsRemaining;
                    UpdateProcessingOverlayText();
                });
            }
        };
        boolean started = audioMode
                ? DroidVideoRecorder.ReduceAudioNoise(this, video, listener)
                : DroidVideoRecorder.EnhanceVideo(this, video, listener);

        if (!started) {
            SetEnhancementState(false);
            Toast.makeText(this, getString(audioMode
                    ? R.string.revisao_video_voz_erro
                    : R.string.revisao_video_melhorar_erro), Toast.LENGTH_SHORT).show();
            StartVideo();
        }
    }

    private void RestoreOriginalVideo() {
        if (enhancingVideo || deleted || (originalVideoUri == null && originalVideoPath == null)) {
            return;
        }

        ReleasePlayer("restore original video");
        DeleteEnhancedVideoIfPossible();
        videoUri = originalVideoUri;
        videoPath = originalVideoPath;
        displayName = originalDisplayName;
        enhancedVideoUri = null;
        enhancedVideoPath = null;
        if (restoreControl != null) {
            restoreControl.setVisibility(View.GONE);
        }
        Toast.makeText(this, getString(R.string.revisao_video_restaurado), Toast.LENGTH_SHORT).show();
        StartVideo();
    }

    private void PausePlayerForProcessing() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            playing = false;
            UpdatePlayState();
        }
    }

    private void DeleteEnhancedVideoIfPossible() {
        try {
            if (enhancedVideoUri != null) {
                getContentResolver().delete(enhancedVideoUri, null, null);
            } else if (enhancedVideoPath != null) {
                new File(enhancedVideoPath).delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void SetEnhancementState(boolean enabled) {
        enhancingVideo = enabled;
        if (enabled) {
            processingAnimationStep = 0;
            progressHandler.removeCallbacks(processingAnimator);
            progressHandler.post(processingAnimator);
        } else {
            progressHandler.removeCallbacks(processingAnimator);
            processingProgressPercent = -1;
            processingEstimatedSeconds = -1;
            processingAudioMode = false;
        }
        SetControlsEnabled(controlsPanel, !enabled);
        if (controlsPanel != null) {
            controlsPanel.setAlpha(enabled ? 0.45f : 1f);
        }
        if (enabled) {
            UpdateProcessingOverlayText();
        } else {
            HideStatus();
        }
        SetControlsEnabled(closeButton, !enabled);
        if (closeButton != null) {
            closeButton.setAlpha(enabled ? 0.45f : 1f);
        }

        if (enabled) {
            ShowEnhancementOverlay();
        } else if (enhancementOverlay != null) {
            rootView.removeView(enhancementOverlay);
            enhancementOverlay = null;
        }
    }

    private void ShowEnhancementOverlay() {
        if (rootView == null || enhancementOverlay != null) {
            return;
        }

        enhancementOverlay = new FrameLayout(this);
        enhancementOverlay.setBackgroundColor(Color.TRANSPARENT);

        ProcessingStarsView starsView = new ProcessingStarsView(this);
        enhancementOverlay.addView(starsView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        UpdateProcessingOverlayText();
        rootView.addView(enhancementOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void UpdateStatus(String title, String detail) {
        if (statusPanel == null || statusTitleLabel == null || statusDetailLabel == null) {
            return;
        }
        statusPanel.setVisibility(View.VISIBLE);
        statusTitleLabel.setText(title);
        if (detail == null || detail.length() == 0) {
            statusDetailLabel.setVisibility(View.GONE);
        } else {
            statusDetailLabel.setText(detail);
            statusDetailLabel.setVisibility(View.VISIBLE);
        }
    }

    private void HideStatus() {
        if (statusPanel != null) {
            statusPanel.setVisibility(View.GONE);
        }
    }

    private void UpdateProcessingOverlayText() {
        if (processingProgressPercent < 0) {
            UpdateStatus(getAnimatedProcessingTitle(), getString(processingSummaryRes));
            return;
        }

        UpdateStatus(getAnimatedProcessingTitle(),
                getString(processingSummaryRes) + " • " + processingProgressPercent + "%");
    }

    private String getAnimatedProcessingTitle() {
        String baseTitle = getString(processingTitleRes).replace("...", "");
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < processingAnimationStep; i++) {
            dots.append('.');
        }
        return baseTitle + dots;
    }

    private class ProcessingStarsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path starPath = new Path();
        private final long startedAtMs = System.currentTimeMillis();
        private final float[] particleX = {.12f, .22f, .34f, .48f, .62f, .76f, .88f, .18f, .42f, .68f, .82f, .52f};
        private final float[] particleY = {.18f, .66f, .32f, .82f, .22f, .58f, .38f, .42f, .12f, .74f, .84f, .50f};
        private final float[] particleDelays = {0f, .16f, .34f, .08f, .48f, .25f, .58f, .42f, .72f, .62f, .88f, .52f};
        private final float[] particleSizes = {12f, 18f, 24f, 13f, 20f, 14f, 16f, 10f, 15f, 22f, 11f, 30f};
        private final float[] noteX = {.08f, .14f, .20f, .27f, .34f, .42f, .50f, .58f, .65f, .72f, .80f, .88f, .94f, .18f, .38f, .62f, .78f, .46f};
        private final float[] noteY = {.72f, .30f, .88f, .20f, .58f, .80f, .34f, .16f, .70f, .42f, .84f, .50f, .26f, .46f, .12f, .92f, .62f, .52f};
        private final float[] noteDelays = {.10f, .36f, .66f, .04f, .44f, .24f, .78f, .54f, .88f, .18f, .62f, .72f, .30f, .94f, .50f, .82f, .14f, .58f};
        private final float[] noteSizes = {28f, 18f, 24f, 34f, 20f, 30f, 17f, 26f, 38f, 19f, 32f, 22f, 16f, 27f, 21f, 35f, 25f, 42f};
        private final int[] particleColors = {
                Color.rgb(109, 214, 238),
                Color.rgb(150, 214, 184),
                Color.rgb(166, 154, 245),
                Color.rgb(245, 209, 112),
                Color.rgb(117, 190, 255)
        };

        ProcessingStarsView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height / 2f;
            float elapsed = (System.currentTimeMillis() - startedAtMs) / 1000f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(46, 31, 32, 37));
            canvas.drawRect(0, 0, width, height, paint);

            DrawSparkle(canvas, centerX, centerY - dp(68),
                    dp(26) + dp(5) * Pulse(elapsed, .2f),
                    dp(12),
                    235,
                    Color.rgb(150, 214, 184));
            for (int i = 0; i < particleX.length; i++) {
                float cycle = ((elapsed * 0.68f) + particleDelays[i]) % 1f;
                float eased = EaseOut(cycle);
                float drift = (float) Math.sin(elapsed * .9f + i * 1.7f) * dp(18);
                float x = width * particleX[i] + drift;
                float y = height * particleY[i] - eased * dp(42);
                float fade = cycle < .24f ? cycle / .24f : Math.max(0f, (1f - cycle) / .52f);
                int alpha = Math.max(0, Math.min(190, Math.round(190 * fade)));
                float outer = dp(Math.round(particleSizes[i])) * (0.75f + eased * .40f);
                DrawSparkle(canvas, x, y, outer, outer * .45f, alpha,
                        particleColors[i % particleColors.length]);
            }
            if (processingAudioMode) {
                for (int i = 0; i < noteX.length; i++) {
                    float cycle = ((elapsed * 0.96f) + noteDelays[i]) % 1f;
                    float eased = EaseOut(cycle);
                    float sway = (float) Math.sin(elapsed * 2.4f + i) * dp(24);
                    float x = width * noteX[i] + sway;
                    float y = height * noteY[i] - eased * dp(92);
                    float fade = cycle < .20f ? cycle / .20f : Math.max(0f, (1f - cycle) / .55f);
                    int alpha = Math.max(0, Math.min(215, Math.round(215 * fade)));
                    float size = dp(Math.round(noteSizes[i])) * (0.86f + eased * .30f);
                    DrawMusicNote(canvas, x, y, size, alpha,
                            particleColors[(i + 1) % particleColors.length],
                            (float) Math.sin(elapsed + i) * 8f);
                }
            }

            invalidate();
        }

        private float Pulse(float elapsed, float offset) {
            return (float) ((Math.sin((elapsed + offset) * Math.PI * 2f) + 1f) * 0.5f);
        }

        private float EaseOut(float value) {
            return 1f - (1f - value) * (1f - value);
        }

        private void DrawSparkle(Canvas canvas, float centerX, float centerY,
                                 float outerRadius, float innerRadius, int alpha, int color) {
            starPath.reset();
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(-90 + i * 45);
                float radius = i % 2 == 0 ? outerRadius : innerRadius;
                float x = centerX + (float) Math.cos(angle) * radius;
                float y = centerY + (float) Math.sin(angle) * radius;
                if (i == 0) {
                    starPath.moveTo(x, y);
                } else {
                    starPath.lineTo(x, y);
                }
            }
            starPath.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(70, 8, 18, 22));
            canvas.save();
            canvas.translate(dp(1), dp(1));
            canvas.drawPath(starPath, paint);
            canvas.restore();
            paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawPath(starPath, paint);
        }

        private void DrawMusicNote(Canvas canvas, float x, float y, float size,
                                   int alpha, int color, float rotation) {
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(2f, size * .13f));

            canvas.save();
            canvas.rotate(rotation, x, y);

            paint.setColor(Color.argb(65, 8, 18, 22));
            DrawMusicNoteShape(canvas, x + dp(1), y + dp(1), size);

            paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            DrawMusicNoteShape(canvas, x, y, size);

            canvas.restore();
        }

        private void DrawMusicNoteShape(Canvas canvas, float x, float y, float size) {
            float headWidth = size * .58f;
            float headHeight = size * .38f;
            RectF head = new RectF(
                    x - headWidth * .55f,
                    y + size * .22f,
                    x + headWidth * .45f,
                    y + size * .22f + headHeight);
            canvas.drawOval(head, paint);

            float stemX = x + headWidth * .34f;
            float stemTop = y - size * .58f;
            float stemBottom = y + size * .33f;
            canvas.drawLine(stemX, stemBottom, stemX, stemTop, paint);

            Path flag = new Path();
            flag.moveTo(stemX, stemTop);
            flag.cubicTo(stemX + size * .44f, stemTop + size * .08f,
                    stemX + size * .48f, stemTop + size * .34f,
                    stemX + size * .08f, stemTop + size * .42f);
            flag.lineTo(stemX, stemTop + size * .26f);
            flag.close();
            canvas.drawPath(flag, paint);
        }
    }

    private void SetControlsEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SetControlsEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    private Uri GetShareUri() {
        if (videoUri != null) {
            return videoUri;
        }
        if (videoPath == null) {
            return null;
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(videoPath));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
