package com.droid.videoRecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.animation.Animator;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private TextView subtitleLabel;
    private TextView closeButton;
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
    private int processingTitleRes = R.string.revisao_video_melhorando;
    private int processingSummaryRes = R.string.revisao_video_melhorando_resumo;
    private int revealCenterX;
    private int revealCenterY;
    private int revealRadius;

    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            UpdateProgress();
            progressHandler.postDelayed(this, 250);
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

        DroidHeadService.SetHiddenByReview(true);
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
        ReleasePlayer();
        DroidHeadService.SetHiddenByReview(false);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TRASH_VIDEO) {
            return;
        }

        if (resultCode == RESULT_OK) {
            deleted = true;
            Toast.makeText(this, getString(R.string.revisao_video_movido_lixeira), Toast.LENGTH_SHORT).show();
            finish();
        } else {
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

        videoContainer = new FrameLayout(this);
        videoContainer.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams videoContainerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        videoContainerParams.setMargins(0, dp(110), 0, dp(182));
        rootView.addView(videoContainer, videoContainerParams);

        videoView = new PlayerView(this);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setUseController(false);
        videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoView.setShutterBackgroundColor(Color.BLACK);
        videoContainer.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(22), dp(28), dp(22), dp(18));
        header.setBackgroundColor(Color.argb(178, 0, 0, 0));

        TextView title = new TextView(this);
        title.setText(getString(R.string.revisao_video_titulo));
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(24);
        header.addView(title);

        subtitleLabel = new TextView(this);
        subtitleLabel.setText(getString(R.string.revisao_video_subtitulo));
        subtitleLabel.setTextColor(Color.rgb(190, 193, 201));
        subtitleLabel.setTextSize(14);
        subtitleLabel.setPadding(0, dp(4), 0, 0);
        header.addView(subtitleLabel);

        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        rootView.addView(header, headerParams);

        closeButton = new TextView(this);
        closeButton.setText("X");
        closeButton.setTextColor(Color.WHITE);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setTypeface(Typeface.DEFAULT_BOLD);
        closeButton.setTextSize(16);
        closeButton.setBackground(CreateRounded(Color.rgb(54, 56, 64), dp(21)));
        closeButton.setOnClickListener(v -> {
            if (!enhancingVideo) {
                finish();
            }
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(42), dp(42),
                Gravity.TOP | Gravity.RIGHT);
        closeParams.setMargins(0, dp(28), dp(18), 0);
        rootView.addView(closeButton, closeParams);

        LinearLayout progressPanel = CreateProgressPanel();
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        progressParams.setMargins(dp(18), 0, dp(18), dp(118));
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
        if (DroidPrefsUtils.melhorarVideo(this)) {
            controls.addView(CreateControl(android.R.drawable.ic_menu_edit,
                    getString(R.string.revisao_video_melhorar),
                    Color.rgb(38, 115, 148),
                    v -> EnhanceVideo()), controlItemParams);
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
        button.setColorFilter(Color.WHITE);
        button.setBackground(CreateRounded(color, dp(22)));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
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
        PrepareVideo();
    }

    private void PrepareVideo() {
        try {
            ReleasePlayer();
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
                    Toast.makeText(DroidVideoReviewActivity.this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
                    playing = false;
                    UpdatePlayState();
                }
            });
            mediaPlayer.prepare();
            mediaPlayer.play();
        } catch (Exception ex) {
            Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
            playing = false;
            UpdatePlayState();
        }
    }

    private void ReleasePlayer() {
        StopProgressUpdates();
        if (mediaPlayer != null) {
            videoView.setPlayer(null);
            mediaPlayer.release();
            mediaPlayer = null;
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.revisao_video_confirmar_apagar_titulo))
                .setMessage(getString(R.string.revisao_video_confirmar_apagar_mensagem))
                .setNegativeButton(getString(R.string.revisao_video_cancelar), null)
                .setPositiveButton(getString(R.string.revisao_video_mover_lixeira), (dialogInterface, which) -> MoveVideoToTrash())
                .create();
        dialog.setOnShowListener(dialogInterface -> {
            dialog.getWindow().setBackgroundDrawable(CreateRounded(Color.rgb(34, 35, 39), dp(22)));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.rgb(190, 193, 201));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.rgb(232, 65, 72));
        });
        dialog.show();
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
                ReleasePlayer();
                if (MoveMediaStoreVideoToTrashDirectly()) {
                    deleted = true;
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
                StartVideo();
                Toast.makeText(this, getString(R.string.revisao_video_erro), Toast.LENGTH_SHORT).show();
                return;
            } catch (Exception ignored) {
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
        } catch (Exception ignored) {
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
            ReleasePlayer();
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
        if (enhancingVideo || deleted) {
            return;
        }

        DroidVideoRecorder.RecordedVideo video = new DroidVideoRecorder.RecordedVideo(
                videoUri,
                videoPath,
                displayName);
        if (!video.HasVideo()) {
            Toast.makeText(this, getString(R.string.revisao_video_melhorar_erro), Toast.LENGTH_SHORT).show();
            return;
        }

        processingTitleRes = R.string.revisao_video_melhorando;
        processingSummaryRes = R.string.revisao_video_melhorando_resumo;
        ReleasePlayer();
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
                            getString(R.string.revisao_video_melhorado),
                            Toast.LENGTH_SHORT).show();
                    StartVideo();
                });
            }

            @Override
            public void OnVideoEnhancementFailed() {
                runOnUiThread(() -> {
                    SetEnhancementState(false);
                    Toast.makeText(DroidVideoReviewActivity.this,
                            getString(R.string.revisao_video_melhorar_erro),
                            Toast.LENGTH_SHORT).show();
                    StartVideo();
                });
            }
        };
        boolean started = DroidVideoRecorder.EnhanceVideo(this, video, listener);

        if (!started) {
            SetEnhancementState(false);
            Toast.makeText(this, getString(R.string.revisao_video_melhorar_erro), Toast.LENGTH_SHORT).show();
            StartVideo();
        }
    }

    private void RestoreOriginalVideo() {
        if (enhancingVideo || deleted || (originalVideoUri == null && originalVideoPath == null)) {
            return;
        }

        ReleasePlayer();
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
        if (subtitleLabel != null) {
            subtitleLabel.setText(enabled
                    ? getString(processingTitleRes)
                    : getString(R.string.revisao_video_subtitulo));
        }
        SetControlsEnabled(controlsPanel, !enabled);
        if (controlsPanel != null) {
            controlsPanel.setAlpha(enabled ? 0.45f : 1f);
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
        enhancementOverlay.setBackgroundColor(Color.argb(90, 0, 0, 0));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(22), dp(16), dp(22), dp(16));
        panel.setBackground(CreateRounded(Color.rgb(34, 35, 39), dp(24)));

        TextView title = new TextView(this);
        title.setText(getString(processingTitleRes));
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);

        TextView summary = new TextView(this);
        summary.setText(getString(processingSummaryRes));
        summary.setTextColor(Color.rgb(190, 193, 201));
        summary.setTextSize(12);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(0, dp(6), 0, 0);
        panel.addView(summary);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        rootView.addView(enhancementOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        enhancementOverlay.addView(panel, panelParams);
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
