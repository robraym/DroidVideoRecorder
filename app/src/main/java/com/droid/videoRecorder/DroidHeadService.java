package com.droid.videoRecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.*;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.lang.ref.WeakReference;


public class DroidHeadService extends Service implements TextToSpeech.OnInitListener, SensorEventListener {
    private static final int FOREGROUND_NOTIFICATION_ID = 100;
    private static final String NOTIFICATION_CHANNEL_ID = "droid_video_recorder_service";
    private static final int CHAT_HEAD_DEFAULT_SIZE_DP = 122;
    private static final int CHAT_HEAD_MIN_SIZE_DP = 84;
    private static final int CHAT_HEAD_MIN_TOUCH_SIZE_DP = 320;
    private static final int CHAT_HEAD_SCREEN_MARGIN_DP = 30;
    private static final float CHAT_HEAD_FULL_PINCH_RANGE = 1.20f;
    private static final int TRASH_TARGET_WIDTH_DP = 104;
    private static final int TRASH_TARGET_HEIGHT_DP = 108;
    private static final int PREVIEW_DISABLED_ALPHA = 153;
    private static final int COLOR_READY_PREVIEW_OFF = Color.rgb(34, 142, 30);
    private static final int COLOR_RECORDING_PREVIEW_OFF = Color.rgb(142, 34, 29);
    private static final int CAMERA_INDICATOR_AFTER_ZOOM_DELAY_MS = 300;
    private static final float TWIST_THRESHOLD = 5.5f;
    private static final long TWIST_SEQUENCE_TIMEOUT_MS = 900;
    private static final long TWIST_COOLDOWN_MS = 1800;
    private static boolean serviceActive;
    private static WeakReference<DroidHeadService> activeService = new WeakReference<>(null);

    private WindowManager windowManager;
    private ImageView chatHead;
    private View touchTarget;
    private TrashTargetView trashTarget;
    private SettingsTargetView settingsTarget;
    private TextView txtHead;
    private TextView txtCameraBadge;
    private SurfaceView mSurfaceView;
    private TextureView readyPreviewView;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private int orientationEvent;
    private int chatHeadSizeDp = CHAT_HEAD_DEFAULT_SIZE_DP;
    private Context context;
    private AsyncTask asyncTask;
    private View.OnTouchListener onTouchListener;
    private boolean pendingPreview;
    private DroidConstants.EnumTypeViewCam pendingPreviewCam = DroidConstants.EnumTypeViewCam.FacingBack;
    private boolean pendingReadyPreview;
    private boolean trashDragActive;
    private boolean trashTargetHighlighted;
    private boolean settingsTargetHighlighted;
    private boolean closingFromTrash;
    private boolean resizeBubbleIndicatorActive;
    private boolean serviceResourcesReleased;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PalmGestureDetector palmGestureDetector;
    private boolean palmCountdownActive;
    private int palmCountdownValue;
    private boolean videoProcessingActive;
    private int videoProcessingDots;
    private long videoProcessingStartedAtMs;
    private ProcessingDrawable processingDrawable;
    private SensorManager sensorManager;
    private Sensor gyroscopeSensor;
    private Notification.Builder notificationBuilder;
    private NotificationManager notificationManager;
    private int lastTwistDirection;
    private int twistCount;
    private long lastTwistMoveTime;
    private long lastTwistCommandTime;

    private boolean necessarioComandoDepoisDoInit = false;
    private TextToSpeech tts;
    private ArrayList<DroidConstants.EnumStateRecVideo> stateRecVideoSTOP;
    private ArrayList<DroidConstants.EnumStateRecVideo> stateRecVideoVIEW;
    private ArrayList<DroidConstants.EnumStateRecVideo> stateRecVideoREC;
    private ArrayList<DroidConstants.EnumStateRecVideo> stateRecVideoCLOSE;

    private final Runnable videoProcessingPulse = new Runnable() {
        @Override
        public void run() {
            if (!videoProcessingActive) {
                return;
            }

            videoProcessingDots = (videoProcessingDots % 3) + 1;
            StringBuilder text = new StringBuilder(getString(R.string.video_processing_bubble));
            for (int i = 0; i < videoProcessingDots; i++) {
                text.append(".");
            }
            txtHead.setText(text.toString());
            if (processingDrawable != null) {
                processingDrawable.invalidateSelf();
            }
            mainHandler.postDelayed(this, 700);
        }
    };
    private final Runnable delayedCameraIndicatorAfterZoom = new Runnable() {
        @Override
        public void run() {
            if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP
                    && !DroidPrefsUtils.exibePreviaCamera(context)) {
                txtHead.setText("");
                txtHead.invalidate();
                txtHead.setVisibility(View.INVISIBLE);
                txtHead.setSingleLine(false);
                chatHead.setImageDrawable(null);
                chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_READY_PREVIEW_OFF));
                ShowCameraIndicator();
            }
        }
    };

    OrientationEventListener myOrientationEventListener;

    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    WindowManager.LayoutParams surfaceParams = new WindowManager.LayoutParams(
            1,
            1,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    WindowManager.LayoutParams readyPreviewParams = new WindowManager.LayoutParams(
            1,
            1,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    WindowManager.LayoutParams touchParams = new WindowManager.LayoutParams(
            1,
            1,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    WindowManager.LayoutParams trashTargetParams = new WindowManager.LayoutParams(
            1,
            1,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    WindowManager.LayoutParams settingsTargetParams = new WindowManager.LayoutParams(
            1,
            1,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    private static int getOverlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int GetMaxChatHeadSizeDp() {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            return CHAT_HEAD_DEFAULT_SIZE_DP;
        }

        int screenWidthDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        int screenHeightDp = Math.round(getResources().getDisplayMetrics().heightPixels / density);
        int shortestSideDp = Math.min(screenWidthDp, screenHeightDp);
        return Math.max(CHAT_HEAD_MIN_SIZE_DP, shortestSideDp - CHAT_HEAD_SCREEN_MARGIN_DP);
    }

    private int BubbleDp(int baseValue) {
        return Math.max(1, Math.round(baseValue * chatHeadSizeDp / (float) CHAT_HEAD_DEFAULT_SIZE_DP));
    }

    private float BubbleTextSize(float baseValue) {
        return Math.max(1f, baseValue * chatHeadSizeDp / CHAT_HEAD_DEFAULT_SIZE_DP);
    }

    private int GetTouchTargetSizeDp() {
        int touchSizeDp = Math.min(GetMaxChatHeadSizeDp(), CHAT_HEAD_MIN_TOUCH_SIZE_DP);
        return Math.max(chatHeadSizeDp, touchSizeDp);
    }

    private void TimeSleep(Integer seg) {
        try {
            Thread.sleep(seg);
        } catch (Exception ex) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Not used
        return null;
    }

    @Override
    public void onInit(int status) {
        necessarioComandoDepoisDoInit = true;
        Abrir();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("DVR", "DroidHeadService onStartCommand");
        if (serviceResourcesReleased) {
            context = getBaseContext();
            DroidVideoRecorder.TypeViewCam = DroidPrefsUtils.obtemUltimaCamera(context);
            serviceActive = true;
            activeService = new WeakReference<>(this);
            serviceResourcesReleased = false;
            StartForegroundServiceNotification();
            InicializarVariavel();
            InicializarAcao();
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getBaseContext();
        DroidVideoRecorder.TypeViewCam = DroidPrefsUtils.obtemUltimaCamera(context);
        serviceActive = true;
        activeService = new WeakReference<>(this);
        Log.d("DVR", "DroidHeadService onCreate");
        StartForegroundServiceNotification();
        InicializarVariavel();
        InicializarAcao();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        //call widget update methods/services/broadcasts
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
            DroidVideoRecorder.OnInitRec(getResources().getConfiguration(), orientationEvent, DroidVideoRecorder.TypeViewCam);
        }
    }

    @Override
    public void onDestroy() {
        Log.d("DVR", "DroidHeadService onDestroy");
        ReleaseServiceResources();
        super.onDestroy();
    }

    private void ReleaseServiceResources() {
        if (serviceResourcesReleased) {
            return;
        }
        serviceResourcesReleased = true;
        serviceActive = false;
        if (activeService.get() == this) {
            activeService.clear();
        }
        mainHandler.removeCallbacks(delayedCameraIndicatorAfterZoom);
        CancelPalmRecordingCountdown();
        PausePalmGestureDetection();
        DroidVideoRecorder.ReleaseForServiceStop();
        RemoveOverlayView(touchTarget);
        RemoveOverlayView(chatHead);
        RemoveOverlayView(txtHead);
        RemoveOverlayView(txtCameraBadge);
        RemoveOverlayView(mSurfaceView);
        RemoveOverlayView(readyPreviewView);
        RemoveOverlayView(trashTarget);
        RemoveOverlayView(settingsTarget);
        if (palmGestureDetector != null) {
            palmGestureDetector.Close();
            palmGestureDetector = null;
        }
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Exception ignored) {
            }
            sensorManager = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        Vibrar(100);
    }

    private void RemoveOverlayView(View view) {
        if (view == null || windowManager == null) {
            return;
        }

        try {
            windowManager.removeView(view);
        } catch (Exception ignored) {
        }
    }

    public static boolean IsActive() {
        return serviceActive;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d("DVR", "DroidHeadService onTaskRemoved");
        if (DroidPrefsUtils.exibeTelaInicial(context)) {
            Intent restartService = new Intent(getApplicationContext(), DroidHeadService.class);
            startService(restartService);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void StartForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Video Recorder",
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        } else {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }

        Intent notificationIntent = new Intent(this, DroidConfigurationActivity.class);
        notificationIntent.putExtra(DroidConstants.CHAMADAPELOSERVICO, true);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        notificationBuilder = new Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder.setChannelId(NOTIFICATION_CHANNEL_ID);
        }

        notificationBuilder.setContentText(GetReadyNotificationText());
        startForeground(FOREGROUND_NOTIFICATION_ID, notificationBuilder.build());
    }

    private void UpdateNotification(String text) {
        if (notificationBuilder == null || notificationManager == null) {
            return;
        }

        notificationBuilder.setContentText(text);
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notificationBuilder.build());
    }

    private String GetCameraName(DroidConstants.EnumTypeViewCam typeViewCam) {
        if (typeViewCam == DroidConstants.EnumTypeViewCam.FacingFront) {
            return getString(R.string.notification_front_camera);
        }
        return getString(R.string.notification_back_camera);
    }

    private String GetReadyNotificationText() {
        return getString(R.string.notification_ready_to_record_with_camera,
                GetCameraName(DroidVideoRecorder.TypeViewCam));
    }

    private String GetRecordingNotificationText() {
        return getString(R.string.notification_recording_with_camera,
                GetCameraName(DroidVideoRecorder.TypeViewCam));
    }

    private String GetViewingNotificationText() {
        return getString(R.string.notification_viewing_with_camera,
                GetCameraName(DroidVideoRecorder.TypeViewCam));
    }

    private String GetCameraIndicatorText() {
        if (DroidVideoRecorder.TypeViewCam == DroidConstants.EnumTypeViewCam.FacingFront) {
            return getString(R.string.camera_label_front);
        }
        return getString(R.string.camera_label_back);
    }

    private String GetCameraBadgeText() {
        if (DroidVideoRecorder.TypeViewCam == DroidConstants.EnumTypeViewCam.FacingFront) {
            return getString(R.string.camera_indicator_front);
        }
        return getString(R.string.camera_indicator_back);
    }

    private void ShowCameraIndicator() {
        txtHead.setText("");
        txtHead.setVisibility(View.INVISIBLE);
        txtCameraBadge.setText(GetCameraIndicatorText().toUpperCase(Locale.getDefault()));
        txtCameraBadge.setTextSize(BubbleTextSize(14.2f));
        txtCameraBadge.setSingleLine(true);
        txtCameraBadge.setGravity(Gravity.CENTER);
        txtCameraBadge.setPadding(0, 0, 0, 0);
        txtCameraBadge.setScaleX(1f);
        txtCameraBadge.setScaleY(1f);
        txtCameraBadge.setShadowLayer(4, 0, 1, Color.BLACK);
        txtCameraBadge.setVisibility(View.VISIBLE);
    }

    private void HideCameraIndicator() {
        txtCameraBadge.setText("");
        txtCameraBadge.setAlpha(1f);
        txtCameraBadge.setVisibility(View.INVISIBLE);
        txtCameraBadge.invalidate();
    }

    private void ShowRecordingBadge() {
        txtCameraBadge.setText(GetCameraBadgeText());
        txtCameraBadge.setTextSize(BubbleTextSize(9));
        txtCameraBadge.setSingleLine(true);
        txtCameraBadge.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        txtCameraBadge.setPadding(0, 0, 0, dp(BubbleDp(5)));
        txtCameraBadge.setScaleX(1f);
        txtCameraBadge.setScaleY(1f);
        txtCameraBadge.setShadowLayer(3, 0, 1, Color.BLACK);
        txtCameraBadge.setVisibility(View.VISIBLE);
    }

    private void HideRecordingBadge() {
        if (txtCameraBadge != null) {
            txtCameraBadge.setVisibility(View.INVISIBLE);
        }
    }

    private void ShowRecordingTimer() {
        txtHead.setText("00:00");
        txtHead.setTextSize(BubbleTextSize(12));
        txtHead.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        txtHead.setPadding(0, 0, 0, dp(BubbleDp(8)));
        if (chatHead.getVisibility() == View.VISIBLE) {
            txtHead.setVisibility(View.VISIBLE);
        }
    }

    private void InicializarVariavel() {
        context = getBaseContext();
        chatHeadSizeDp = DroidPrefsUtils.obtemTamanhoBolinha(
                context,
                CHAT_HEAD_DEFAULT_SIZE_DP,
                CHAT_HEAD_MIN_SIZE_DP,
                GetMaxChatHeadSizeDp());

        windowManager = (WindowManager) context.getSystemService(WINDOW_SERVICE);

        mSurfaceView = new SurfaceView(context);
        mSurfaceView.setLayoutParams(surfaceParams);
        mSurfaceView.getHolder().setFixedSize(1, 1);
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                StartPendingPreview();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                StartPendingPreview();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                pendingPreview = false;
            }
        });

        readyPreviewView = new TextureView(context);
        readyPreviewView.setOpaque(false);
        readyPreviewView.setVisibility(View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            readyPreviewView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            readyPreviewView.setClipToOutline(true);
        }
        readyPreviewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                ApplyPreviewTransform();
                StartPendingReadyPreview();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                ApplyPreviewTransform();
                StartPendingReadyPreview();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                pendingReadyPreview = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Not used
            }
        });
        palmGestureDetector = new PalmGestureDetector(context, readyPreviewView,
                () -> mainHandler.post(this::StartPalmRecordingCountdown));

        chatHead = new ImageView(context);
        chatHead.setImageResource(R.mipmap.viewrec);
        params.width = dp(chatHeadSizeDp);
        params.height = dp(chatHeadSizeDp);
        touchTarget = new View(context);
        txtHead = new TextView(context);
        txtHead.setText("00:00");
        txtHead.setTextColor(Color.WHITE);
        txtHead.setTypeface(Typeface.DEFAULT_BOLD);
        txtHead.setGravity(Gravity.CENTER);
        txtHead.setShadowLayer(3, 0, 1, Color.BLACK);
        txtHead.setVisibility(View.INVISIBLE);
        txtCameraBadge = new TextView(context);
        txtCameraBadge.setTextColor(Color.WHITE);
        txtCameraBadge.setTypeface(Typeface.DEFAULT_BOLD);
        txtCameraBadge.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        txtCameraBadge.setPadding(0, 0, 0, dp(BubbleDp(5)));
        txtCameraBadge.setTextSize(BubbleTextSize(9));
        txtCameraBadge.setShadowLayer(3, 0, 1, Color.BLACK);
        txtCameraBadge.setVisibility(View.INVISIBLE);
        trashTarget = new TrashTargetView(context);
        trashTarget.setVisibility(View.INVISIBLE);
        settingsTarget = new SettingsTargetView(context);
        settingsTarget.setVisibility(View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            trashTarget.setElevation(dp(12));
            settingsTarget.setElevation(dp(12));
        }

        params.gravity = Gravity.CENTER;
        touchParams.gravity = Gravity.CENTER;
        touchParams.width = dp(GetTouchTargetSizeDp());
        touchParams.height = dp(GetTouchTargetSizeDp());
        surfaceParams.gravity = Gravity.CENTER;
        readyPreviewParams.gravity = Gravity.CENTER;
        trashTargetParams.width = dp(TRASH_TARGET_WIDTH_DP);
        trashTargetParams.height = dp(TRASH_TARGET_HEIGHT_DP);
        trashTargetParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        trashTargetParams.y = dp(18);
        settingsTargetParams.width = dp(TRASH_TARGET_WIDTH_DP);
        settingsTargetParams.height = dp(TRASH_TARGET_HEIGHT_DP);
        settingsTargetParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        settingsTargetParams.y = dp(18);
        windowManager.addView(mSurfaceView, surfaceParams);
        windowManager.addView(readyPreviewView, readyPreviewParams);
        windowManager.addView(chatHead, params);
        windowManager.addView(txtHead, params);
        windowManager.addView(txtCameraBadge, params);
        windowManager.addView(touchTarget, touchParams);
        windowManager.addView(trashTarget, trashTargetParams);
        windowManager.addView(settingsTarget, settingsTargetParams);
        tts = new TextToSpeech(context, this);

        DroidVideoRecorder.SetContext(context);
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.STOP;
        DroidVideoRecorder.TypeViewCam = DroidPrefsUtils.obtemUltimaCamera(context);
        DroidVideoRecorder.LocalGravacaoVideo = DroidPrefsUtils.obtemLocalGravacao(context);
        onTouchListener = new TouchListener();
        tts.setLanguage(Locale.getDefault());

        stateRecVideoSTOP = new ArrayList<>();
        stateRecVideoSTOP.add(DroidConstants.EnumStateRecVideo.VIEW);
        stateRecVideoSTOP.add(DroidConstants.EnumStateRecVideo.RECORD);
        stateRecVideoSTOP.add(DroidConstants.EnumStateRecVideo.CLOSE);

        stateRecVideoSTOP = new ArrayList<>();
        stateRecVideoSTOP.add(DroidConstants.EnumStateRecVideo.VIEW);
        stateRecVideoSTOP.add(DroidConstants.EnumStateRecVideo.RECORD);
        stateRecVideoSTOP.add(DroidConstants.EnumStateRecVideo.CLOSE);

        stateRecVideoVIEW = new ArrayList<>();
        stateRecVideoVIEW.add(DroidConstants.EnumStateRecVideo.VIEW);
        stateRecVideoVIEW.add(DroidConstants.EnumStateRecVideo.RECORD);
        stateRecVideoVIEW.add(DroidConstants.EnumStateRecVideo.STOP);

        stateRecVideoREC = new ArrayList<>();
        stateRecVideoREC.add(DroidConstants.EnumStateRecVideo.STOP);

        stateRecVideoCLOSE = new ArrayList<>();
        stateRecVideoCLOSE.add(DroidConstants.EnumStateRecVideo.CLOSE);
        stateRecVideoCLOSE.add(DroidConstants.EnumStateRecVideo.STOP);

        ShowReadyPreview();

    }

    private void InicializarAcao() {
        touchTarget.setOnTouchListener(onTouchListener);
        txtHead.setOnTouchListener(onTouchListener);
        txtCameraBadge.setOnTouchListener(onTouchListener);
        chatHead.setOnTouchListener(onTouchListener);
        sensorManager = (SensorManager) context.getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroscopeSensor != null) {
                sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME);
            } else {
                Log.d("DVR", "Giroscopio nao encontrado para gesto de gravacao");
            }
        }

        myOrientationEventListener = new OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int arg0) {
                // TODO Auto-generated method stub
                orientationEvent = arg0;
            }
        };

    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) {
            return;
        }

        float rotationX = event.values[0];
        float rotationY = event.values[1];
        float dominantRotation = Math.abs(rotationY) >= Math.abs(rotationX) ? rotationY : rotationX;

        if (Math.abs(dominantRotation) < TWIST_THRESHOLD) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastTwistCommandTime < TWIST_COOLDOWN_MS) {
            return;
        }

        int direction = dominantRotation > 0 ? 1 : -1;
        if (now - lastTwistMoveTime > TWIST_SEQUENCE_TIMEOUT_MS) {
            twistCount = 0;
            lastTwistDirection = 0;
        }

        if (direction != lastTwistDirection) {
            twistCount++;
            lastTwistDirection = direction;
            lastTwistMoveTime = now;
        }

        if (twistCount >= 2) {
            twistCount = 0;
            lastTwistDirection = 0;
            lastTwistCommandTime = now;
            ExecutarGestoGravacao();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }

    private void ExecutarGestoGravacao() {
        Log.d("DVR", "Gesto de giro detectado");
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            PararEFechar();
        } else {
            Gravar();
        }
    }

    private void StopService() {
        DroidConfigurationActivity.CloseIfOpen();
        StopServiceWithoutClosingConfiguration();
    }

    private void StopServiceWithoutClosingConfiguration() {
        ReleaseServiceResources();
        stopSelf();
    }

    public static void StopForVideoReview() {
        DroidHeadService service = activeService.get();
        if (service != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.StopService();
            } else {
                service.mainHandler.post(service::StopService);
            }
        }
    }

    private void FalaComAtraso(final String text, int atrasoSeg) {
        if (DroidPrefsUtils.leComando(context)) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            TimeSleep(atrasoSeg * 1000);
        }
    }

    private void Fala(final String text) {
        if (DroidPrefsUtils.leComando(context)) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    private void Abrir() {
        Fala(getString(R.string.abrindoDVR));
    }

    private void AbrirConfig() {
        Fala(getString(R.string.abrindoDVRConfig));
        ShowActivity();
        StopServiceWithoutClosingConfiguration();
    }

    private void Gravar() {
        Gravacao();
    }

    private void Gravacao() {
        if (necessarioComandoDepoisDoInit) {
            if (Permite(DroidVideoRecorder.StateRecVideo.RECORD)) {
                Fala(getString(R.string.gravando));
            }
            ShowRec();
        }
    }

    private void Parar() {
        if (Permite(DroidVideoRecorder.StateRecVideo.STOP)) {
            Fala(getString(R.string.parandoGravacao));
            ShowStopRecord(true);
        }
    }

    private void PararEFechar() {
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            Fala(getString(R.string.parandoGravacao));
            ShowStopRecord(true);
            StopService();
        }
    }

    private void Visualizar() {
        if (Permite(DroidVideoRecorder.StateRecVideo.VIEW)) {
            Fala(getString(R.string.visualizando));
            ShowViewChangingCamera();
        }
    }

    private void VisualizarTrocandoCamera() {
        if (Permite(DroidVideoRecorder.StateRecVideo.VIEW)) {
            ChangeTypeViewCam();
        }
    }

    private void Fechar() {
        if (Permite(DroidVideoRecorder.StateRecVideo.CLOSE)) {
            Fala(getString(R.string.fechando));
            ShowClose();
        }
    }

    private void Sair() {
        if (Permite(DroidVideoRecorder.StateRecVideo.CLOSE)) {
            FalaComAtraso(getString(R.string.saindoDVR), 2);
            StopService();
        }
    }

    private void ShowViewChangingCamera() {
        HideReadyPreview();
        ChangeSavedTypeViewCam();
        ShowReadyPreviewAfterCameraChange();
    }

    private void ChangeTypeViewCam() {
        HideReadyPreview();
        ChangeSavedTypeViewCam();
        ShowReadyPreviewAfterCameraChange();
    }

    private void ShowReadyPreviewAfterCameraChange() {
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.STOP;
        ShowReadyPreview();
        UpdateNotification(GetReadyNotificationText());
        Vibrar(100);
        PlayCameraSwitchSound();
    }

    private void ChangeSavedTypeViewCam() {
        if (DroidVideoRecorder.TypeViewCam == DroidConstants.EnumTypeViewCam.FacingBack) {
            Fala(getString(R.string.visualizandoCameraFronta));
            DroidVideoRecorder.TypeViewCam = DroidConstants.EnumTypeViewCam.FacingFront;
        } else {
            Fala(getString(R.string.visualizandoCameraTraseira));
            DroidVideoRecorder.TypeViewCam = DroidConstants.EnumTypeViewCam.FacingBack;
        }

        DroidPrefsUtils.salvaUltimaCamera(context, DroidVideoRecorder.TypeViewCam);
    }

    private void ShowRec() {
        CancelPalmRecordingCountdown();
        PausePalmGestureDetection();
        HideCameraIndicator();
        boolean showCameraPreview = DroidPrefsUtils.exibePreviaCamera(context);
        ShowRecordingPreview(showCameraPreview);
        SetPreviewFullScreen(false);
        boolean recordingStarted = false;

        if (!recordingStarted) {
            ShowRecordingPreview(showCameraPreview);
            DroidVideoRecorder.OnInitRec(getResources().getConfiguration(), orientationEvent, DroidVideoRecorder.TypeViewCam);
            if (showCameraPreview
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    && readyPreviewView != null
                    && readyPreviewView.isAvailable()) {
                ApplyPreviewTransform();
                recordingStarted = DroidVideoRecorder.OnStartRecording(
                        readyPreviewView.getSurfaceTexture(),
                        orientationEvent);
            } else {
                recordingStarted = DroidVideoRecorder.OnStartRecording(mSurfaceView.getHolder(), orientationEvent);
            }
        }

        if (!recordingStarted) {
            ShowStopRecord(false);
            return;
        }
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.RECORD;
        UpdateNotification(GetRecordingNotificationText());
        HideRecordingBadge();
        Vibrar(50);
        asyncTask = new Sincronizar().execute();
    }

    private void ShowStopRecord(boolean record) {
        boolean shouldReviewVideo = record && DroidPrefsUtils.revisarVideoAposGravar(context);
        DroidVideoRecorder.RecordedVideo recordedVideo = DroidVideoRecorder.OnStopRecording(record,
                shouldReviewVideo ? video -> mainHandler.post(() -> {
                    HideVideoProcessing();
                    OpenVideoReview(video);
                }) : null);
        if (record && asyncTask != null) {
            asyncTask.cancel(true);
        }
        if (shouldReviewVideo
                && recordedVideo == null
                && (DroidVideoRecorder.HasPendingVideoProcessing()
                || DroidVideoRecorder.HasPendingDirectVideoReview())) {
            ShowVideoProcessing();
        } else {
            ShowStop();
        }
        if (shouldReviewVideo && recordedVideo != null) {
            OpenVideoReview(recordedVideo);
        }
        Vibrar(50);
    }

    private void OpenVideoReview(DroidVideoRecorder.RecordedVideo video) {
        if (video == null || !video.HasVideo()) {
            return;
        }

        int centerX = 0;
        int centerY = 0;
        int radius = dp(chatHeadSizeDp / 2);
        if (chatHead != null) {
            int[] location = new int[2];
            chatHead.getLocationOnScreen(location);
            centerX = location[0] + chatHead.getWidth() / 2;
            centerY = location[1] + chatHead.getHeight() / 2;
            radius = Math.max(1, chatHead.getWidth() / 2);
        }

        Intent intent = DroidVideoReviewActivity.CreateIntent(context, video, centerX, centerY, radius);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void GetDefaultStop() {
        SetPreviewFullScreen(false);
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.STOP;
        DroidVideoRecorder.OnStopRecording(false);
        UpdateNotification(GetReadyNotificationText());
        ShowReadyPreview();
    }

    private void ShowStop() {
        HideVideoProcessing();
        SetPreviewFullScreen(false);
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.STOP;
        UpdateNotification(GetReadyNotificationText());
        ShowReadyPreview();
    }

    private void ShowVideoProcessing() {
        videoProcessingActive = true;
        videoProcessingDots = 0;
        videoProcessingStartedAtMs = SystemClock.elapsedRealtime();
        CancelPalmRecordingCountdown();
        PausePalmGestureDetection();
        pendingReadyPreview = false;
        SetPreviewFullScreen(false);
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.STOP;
        UpdateNotification(getString(R.string.notification_preparing_video));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (readyPreviewView != null) {
                readyPreviewView.setVisibility(View.INVISIBLE);
            }
            chatHead.setImageDrawable(null);
            processingDrawable = new ProcessingDrawable();
            chatHead.setBackground(processingDrawable);
            processingDrawable.Start();
        } else {
            ShowChatHeadIcon(R.mipmap.viewrec);
        }

        HideRecordingBadge();
        txtHead.setVisibility(View.INVISIBLE);
        mainHandler.removeCallbacks(videoProcessingPulse);
        mainHandler.post(videoProcessingPulse);
    }

    private void HideVideoProcessing() {
        if (!videoProcessingActive) {
            return;
        }
        videoProcessingActive = false;
        mainHandler.removeCallbacks(videoProcessingPulse);
        if (chatHead != null) {
            chatHead.animate().cancel();
            chatHead.setScaleX(1f);
            chatHead.setScaleY(1f);
            chatHead.setAlpha(1f);
            if (processingDrawable != null) {
                processingDrawable.Stop();
                processingDrawable = null;
            }
        }
        if (txtHead != null) {
            txtHead.animate().cancel();
            txtHead.setAlpha(1f);
            txtHead.setSingleLine(false);
            txtHead.setVisibility(View.INVISIBLE);
        }
    }

    private void SetPreviewFullScreen(boolean fullScreen) {
        if (fullScreen) {
            surfaceParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            surfaceParams.height = WindowManager.LayoutParams.MATCH_PARENT;
            surfaceParams.gravity = Gravity.TOP | Gravity.LEFT;
            surfaceParams.x = 0;
            surfaceParams.y = 0;
            mSurfaceView.getHolder().setSizeFromLayout();
        } else {
            surfaceParams.width = 1;
            surfaceParams.height = 1;
            surfaceParams.gravity = Gravity.CENTER;
            surfaceParams.x = 0;
            surfaceParams.y = 0;
            mSurfaceView.getHolder().setFixedSize(1, 1);
        }

        try {
            windowManager.updateViewLayout(mSurfaceView, surfaceParams);
        } catch (Exception ex) {
            Log.d("DVR", ex.getMessage());
        }
    }

    private void ShowReadyPreview() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            ShowChatHeadIcon(R.mipmap.viewrec);
            ShowCameraIndicator();
            return;
        }

        if (!DroidPrefsUtils.exibePreviaCamera(context)) {
            pendingReadyPreview = false;
            if (readyPreviewView != null) {
                readyPreviewView.setVisibility(View.INVISIBLE);
            }
            chatHead.setImageDrawable(null);
            chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_READY_PREVIEW_OFF));
            ShowCameraIndicator();
            PausePalmGestureDetection();
            return;
        }

        chatHead.setImageDrawable(null);
        chatHead.setBackground(null);
        HideCameraIndicator();
        SetPreviewBubble();
        StartReadyPreview(DroidVideoRecorder.TypeViewCam);
        StartPalmGestureDetection();
    }

    private void ShowRecordingPreview() {
        ShowRecordingPreview(true);
    }

    private void ShowRecordingPreview(boolean showPreview) {
        PausePalmGestureDetection();
        pendingReadyPreview = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            ShowChatHeadIcon(R.mipmap.rec);
            return;
        }

        chatHead.setImageDrawable(null);
        if (DroidPrefsUtils.exibePreviaCamera(context)) {
            chatHead.setBackground(CreatePreviewBorder(Color.rgb(232, 65, 72)));
        } else {
            chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_RECORDING_PREVIEW_OFF));
        }
        if (showPreview) {
            SetPreviewBubble();
        } else if (readyPreviewView != null) {
            readyPreviewView.setVisibility(View.INVISIBLE);
        }
    }

    private void HideReadyPreview() {
        CancelPalmRecordingCountdown();
        PausePalmGestureDetection();
        pendingReadyPreview = false;
        if (readyPreviewView != null) {
            readyPreviewView.setVisibility(View.INVISIBLE);
        }
        DroidVideoRecorder.OnStopRecording(false);
        SetPreviewFullScreen(false);
    }

    private void SetPreviewBubble() {
        readyPreviewParams.width = dp(chatHeadSizeDp);
        readyPreviewParams.height = dp(chatHeadSizeDp);
        readyPreviewParams.gravity = Gravity.CENTER;
        readyPreviewParams.x = params.x;
        readyPreviewParams.y = params.y;
        try {
            readyPreviewView.setAlpha(1f);
            windowManager.updateViewLayout(readyPreviewView, readyPreviewParams);
            ApplyPreviewTransform();
            readyPreviewView.setVisibility(View.VISIBLE);
        } catch (Exception ex) {
            Log.d("DVR", ex.getMessage());
        }
    }

    private void ApplyPreviewTransform() {
        if (readyPreviewView == null
                || readyPreviewView.getWidth() == 0
                || readyPreviewView.getHeight() == 0) {
            return;
        }

        float viewWidth = readyPreviewView.getWidth();
        float viewHeight = readyPreviewView.getHeight();
        float aspectRatio = DroidVideoRecorder.GetPreviewAspectRatio();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Matrix matrix = new Matrix();
        matrix.setScale(landscape ? aspectRatio : 1f, landscape ? 1f : aspectRatio,
                viewWidth / 2f,
                viewHeight / 2f);
        readyPreviewView.setTransform(matrix);
    }

    private GradientDrawable CreatePreviewBorder(int color) {
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.OVAL);
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(BubbleDp(3)), color);
        return border;
    }

    private GradientDrawable CreatePreviewOffBubbleBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(
                PREVIEW_DISABLED_ALPHA,
                Color.red(color),
                Color.green(color),
                Color.blue(color)));
        return background;
    }

    private GradientDrawable CreateResizeBubbleBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(178, 24, 27, 31));
        return background;
    }

    private String GetBubbleSizePercentText() {
        return GetBubbleSizePercentText(chatHeadSizeDp);
    }

    private String GetBubbleSizePercentText(int sizeDp) {
        return GetBubbleSizePercent(sizeDp) + "%";
    }

    private int GetBubbleSizePercent(int sizeDp) {
        int maxSizeDp = GetMaxChatHeadSizeDp();
        int sizeRange = Math.max(1, maxSizeDp - CHAT_HEAD_MIN_SIZE_DP);
        int clampedSizeDp = Math.max(CHAT_HEAD_MIN_SIZE_DP, Math.min(maxSizeDp, sizeDp));
        int percent = 1 + Math.round((clampedSizeDp - CHAT_HEAD_MIN_SIZE_DP) * 99f / sizeRange);
        return Math.max(1, Math.min(100, percent));
    }

    private int GetBubbleSizeFromPercent(int percent) {
        int clampedPercent = Math.max(1, Math.min(100, percent));
        int maxSizeDp = GetMaxChatHeadSizeDp();
        int sizeRange = Math.max(1, maxSizeDp - CHAT_HEAD_MIN_SIZE_DP);
        return CHAT_HEAD_MIN_SIZE_DP + Math.round((clampedPercent - 1) * sizeRange / 99f);
    }

    private void ShowResizeBubbleIndicator() {
        resizeBubbleIndicatorActive = true;
        SetTouchTargetExpandedForResize(true);
        mainHandler.removeCallbacks(delayedCameraIndicatorAfterZoom);
        HideCameraIndicator();
        chatHead.setImageDrawable(null);
        chatHead.setBackground(CreateResizeBubbleBackground());
        txtHead.animate().cancel();
        txtHead.setAlpha(1f);
        txtHead.setSingleLine(true);
        txtHead.setText(GetBubbleSizePercentText());
        txtHead.setTextSize(BubbleTextSize(18));
        txtHead.setGravity(Gravity.CENTER);
        txtHead.setPadding(0, 0, 0, 0);
        txtHead.setVisibility(View.VISIBLE);
        ScheduleCameraIndicatorAfterZoomIdle();
    }

    private void UpdateResizeBubbleIndicator(int sizeDp) {
        if (!resizeBubbleIndicatorActive) {
            return;
        }
        HideCameraIndicator();
        chatHead.setBackground(CreateResizeBubbleBackground());
        txtHead.setSingleLine(true);
        txtHead.setText(GetBubbleSizePercentText(sizeDp));
        txtHead.setTextSize(BubbleTextSize(18));
        txtHead.setGravity(Gravity.CENTER);
        txtHead.setPadding(0, 0, 0, 0);
        txtHead.setVisibility(View.VISIBLE);
        ScheduleCameraIndicatorAfterZoomIdle();
    }

    private void ScheduleCameraIndicatorAfterZoomIdle() {
        mainHandler.removeCallbacks(delayedCameraIndicatorAfterZoom);
        mainHandler.postDelayed(
                delayedCameraIndicatorAfterZoom,
                CAMERA_INDICATOR_AFTER_ZOOM_DELAY_MS);
    }

    private void HideResizeBubbleIndicator() {
        if (!resizeBubbleIndicatorActive) {
            return;
        }
        resizeBubbleIndicatorActive = false;
        SetTouchTargetExpandedForResize(false);
        chatHead.setImageDrawable(null);
        if (DroidPrefsUtils.exibePreviaCamera(context)) {
            chatHead.setBackground(null);
            HideCameraIndicator();
            txtHead.setText("");
            txtHead.invalidate();
            txtHead.setVisibility(View.INVISIBLE);
            txtHead.setSingleLine(false);
            mainHandler.removeCallbacks(delayedCameraIndicatorAfterZoom);
        } else {
            chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_READY_PREVIEW_OFF));
        }
    }

    private float GetVideoProcessingProgressFraction() {
        int progressPercent = DroidVideoRecorder.GetVideoProcessingProgressPercent();
        if (progressPercent >= 0) {
            return Math.max(0.02f, Math.min(0.98f, progressPercent / 100f));
        }

        long elapsedMs = Math.max(0, SystemClock.elapsedRealtime() - videoProcessingStartedAtMs);
        float estimatedProgress = 0.10f + elapsedMs / 9000f * 0.76f;
        return Math.max(0.02f, Math.min(0.86f, estimatedProgress));
    }

    private class ProcessingDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path gearPath = new Path();
        private final RectF progressBounds = new RectF();
        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (!videoProcessingActive) {
                    return;
                }
                rotation = (rotation + 4f) % 360f;
                pulse = (pulse + 0.022f) % 1f;
                invalidateSelf();
                mainHandler.postDelayed(this, 48);
            }
        };
        private float rotation;
        private float pulse;

        void Start() {
            mainHandler.removeCallbacks(frame);
            mainHandler.post(frame);
        }

        void Stop() {
            mainHandler.removeCallbacks(frame);
        }

        @Override
        public void draw(Canvas canvas) {
            float width = getBounds().width();
            float height = getBounds().height();
            float size = Math.min(width, height);
            float centerX = getBounds().left + width / 2f;
            float centerY = getBounds().top + height / 2f;
            float radius = size / 2f - dp(BubbleDp(5));
            float progress = GetVideoProcessingProgressFraction();
            float pulseWave = (float) Math.sin(pulse * Math.PI * 2f);

            DrawProcessingCard(canvas, centerX, centerY, radius, pulseWave);
            DrawProgressRing(canvas, centerX, centerY, radius, progress);
            DrawProcessingGear(canvas, centerX, centerY - dp(BubbleDp(18)), pulseWave);
            DrawProcessingText(canvas, centerX, centerY, progress);
        }

        private void DrawProcessingCard(Canvas canvas, float centerX, float centerY, float radius, float pulseWave) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(218, 30, 33, 39));
            paint.setShadowLayer(dp(11), 0, dp(5), Color.argb(100, 0, 0, 0));
            canvas.drawCircle(centerX, centerY, radius, paint);

            paint.clearShadowLayer();
            paint.setShader(new LinearGradient(
                    centerX,
                    centerY - radius,
                    centerX,
                    centerY + radius,
                    new int[]{
                            Color.argb(62, 255, 255, 255),
                            Color.argb(10, 255, 255, 255),
                            Color.argb(56, 0, 0, 0)
                    },
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(BubbleDp(1)));
            paint.setColor(Color.argb(95 + Math.round(20 * Math.max(0f, pulseWave)), 245, 248, 252));
            canvas.drawCircle(centerX, centerY, radius - dp(BubbleDp(1)), paint);
        }

        private void DrawProgressRing(Canvas canvas, float centerX, float centerY, float radius, float progress) {
            float progressRadius = radius * 0.84f;
            progressBounds.set(centerX - progressRadius, centerY - progressRadius,
                    centerX + progressRadius, centerY + progressRadius);

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(BubbleDp(3)));
            paint.setColor(Color.argb(82, 245, 248, 252));
            canvas.drawCircle(centerX, centerY, progressRadius, paint);

            paint.setStrokeWidth(dp(BubbleDp(4)));
            paint.setColor(Color.argb(225, 132, 188, 255));
            canvas.drawArc(progressBounds, -90f, 360f * progress, false, paint);
        }

        private void DrawProcessingGear(Canvas canvas, float centerX, float centerY, float pulseWave) {
            float outerRadius = dp(BubbleDp(13));
            float innerRadius = dp(BubbleDp(10));

            canvas.save();
            canvas.rotate(rotation * 0.25f, centerX, centerY);
            gearPath.reset();
            for (int i = 0; i < 24; i++) {
                double angle = -Math.PI / 2d + i * Math.PI * 2d / 24d;
                float radius = i % 3 == 1 ? innerRadius : outerRadius;
                float x = centerX + (float) Math.cos(angle) * radius;
                float y = centerY + (float) Math.sin(angle) * radius;
                if (i == 0) {
                    gearPath.moveTo(x, y);
                } else {
                    gearPath.lineTo(x, y);
                }
            }
            gearPath.close();

            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(220, 224, 231, 239));
            canvas.drawPath(gearPath, paint);

            paint.setColor(Color.rgb(45, 50, 58));
            canvas.drawCircle(centerX, centerY, dp(BubbleDp(5)), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(BubbleDp(1)));
            paint.setColor(Color.argb(95 + Math.round(50 * Math.max(0f, pulseWave)), 132, 188, 255));
            canvas.drawCircle(centerX, centerY, dp(BubbleDp(6)), paint);
            canvas.restore();
        }

        private void DrawProcessingText(Canvas canvas, float centerX, float centerY, float progress) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setShadowLayer(dp(BubbleDp(3)), 0, dp(BubbleDp(1)), Color.argb(170, 0, 0, 0));

            StringBuilder label = new StringBuilder(getString(R.string.video_processing_bubble));
            for (int i = 0; i < videoProcessingDots; i++) {
                label.append(".");
            }

            paint.setTextSize(BubbleTextSize(8.2f) * getResources().getDisplayMetrics().scaledDensity);
            paint.setColor(Color.WHITE);
            Paint.FontMetrics labelMetrics = paint.getFontMetrics();
            float labelCenterY = centerY + dp(BubbleDp(7));
            float labelBaseline = labelCenterY - (labelMetrics.ascent + labelMetrics.descent) / 2f;
            canvas.drawText(label.toString(), centerX, labelBaseline, paint);

            paint.setTextSize(BubbleTextSize(6.8f) * getResources().getDisplayMetrics().scaledDensity);
            paint.setColor(Color.argb(214, 224, 231, 239));
            Paint.FontMetrics percentMetrics = paint.getFontMetrics();
            float percentCenterY = centerY + dp(BubbleDp(23));
            float percentBaseline = percentCenterY - (percentMetrics.ascent + percentMetrics.descent) / 2f;
            canvas.drawText(Math.round(progress * 100f) + "%", centerX, percentBaseline, paint);
            paint.clearShadowLayer();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private void ResizeReadyBubble(int newSizeDp, boolean persist) {
        if (DroidVideoRecorder.StateRecVideo != DroidConstants.EnumStateRecVideo.STOP) {
            return;
        }

        chatHeadSizeDp = Math.max(CHAT_HEAD_MIN_SIZE_DP, Math.min(GetMaxChatHeadSizeDp(), newSizeDp));
        params.width = dp(chatHeadSizeDp);
        params.height = dp(chatHeadSizeDp);
        int touchSizeDp = resizeBubbleIndicatorActive ? GetMaxChatHeadSizeDp() : GetTouchTargetSizeDp();
        touchParams.width = dp(touchSizeDp);
        touchParams.height = dp(touchSizeDp);
        touchParams.x = params.x;
        touchParams.y = params.y;
        readyPreviewParams.width = dp(chatHeadSizeDp);
        readyPreviewParams.height = dp(chatHeadSizeDp);
        txtCameraBadge.setPadding(0, 0, 0, dp(BubbleDp(5)));
        txtCameraBadge.setTextSize(BubbleTextSize(9));
        if (!resizeBubbleIndicatorActive) {
            if (DroidPrefsUtils.exibePreviaCamera(context)) {
                chatHead.setBackground(null);
            } else {
                chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_READY_PREVIEW_OFF));
            }
        }

        try {
            windowManager.updateViewLayout(readyPreviewView, readyPreviewParams);
            windowManager.updateViewLayout(chatHead, params);
            windowManager.updateViewLayout(txtHead, params);
            windowManager.updateViewLayout(txtCameraBadge, params);
            windowManager.updateViewLayout(touchTarget, touchParams);
            ApplyPreviewTransform();
        } catch (Exception ex) {
            Log.d("DVR", ex.getMessage());
        }

        if (persist) {
            DroidPrefsUtils.salvaTamanhoBolinha(context, chatHeadSizeDp);
        }
    }

    private final Runnable palmCountdownStep = new Runnable() {
        @Override
        public void run() {
            if (!palmCountdownActive
                    || DroidVideoRecorder.StateRecVideo != DroidConstants.EnumStateRecVideo.STOP) {
                CancelPalmRecordingCountdown();
                return;
            }

            if (palmCountdownValue > 0) {
                txtHead.setText(String.valueOf(palmCountdownValue));
                palmCountdownValue--;
                mainHandler.postDelayed(this, 1000);
                return;
            }

            palmCountdownActive = false;
            txtHead.setVisibility(View.INVISIBLE);
            Gravar();
        }
    };

    private void StartPalmGestureDetection() {
        if (!videoProcessingActive && !palmCountdownActive && palmGestureDetector != null) {
            palmGestureDetector.Start();
        }
    }

    private void PausePalmGestureDetection() {
        if (palmGestureDetector != null) {
            palmGestureDetector.Pause();
        }
    }

    private void StartPalmRecordingCountdown() {
        if (palmCountdownActive
                || videoProcessingActive
                || trashDragActive
                || DroidVideoRecorder.StateRecVideo != DroidConstants.EnumStateRecVideo.STOP) {
            return;
        }

        palmCountdownActive = true;
        palmCountdownValue = 3;
        PausePalmGestureDetection();
        txtHead.setTextSize(BubbleTextSize(30));
        txtHead.setGravity(Gravity.CENTER);
        txtHead.setPadding(0, 0, 0, 0);
        txtHead.setVisibility(View.VISIBLE);
        Vibrar(50);
        mainHandler.post(palmCountdownStep);
    }

    private void CancelPalmRecordingCountdown() {
        palmCountdownActive = false;
        mainHandler.removeCallbacks(palmCountdownStep);
        if (DroidVideoRecorder.StateRecVideo != DroidConstants.EnumStateRecVideo.RECORD) {
            txtHead.setVisibility(View.INVISIBLE);
        }
    }

    private void ShowTrashTarget() {
        if (!CanUseDragTargets()) {
            return;
        }

        trashDragActive = true;
        trashTarget.animate().cancel();
        trashTarget.ResetAnimationState();
        UpdateTrashTarget(false);
        trashTarget.setVisibility(View.VISIBLE);
        trashTarget.setAlpha(0f);
        trashTarget.setScaleX(0.72f);
        trashTarget.setScaleY(0.72f);
        trashTarget.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(210)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .start();
        ShowSettingsTarget();
    }

    private void HideTrashTarget() {
        trashDragActive = false;
        UpdateTrashTarget(false);
        trashTarget.animate().cancel();
        trashTarget.animate()
                .alpha(0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(130)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        trashTarget.setVisibility(View.INVISIBLE);
                        trashTarget.ResetAnimationState();
                    }
                })
                .start();
        HideSettingsTarget();
    }

    private void HideTrashTargetImmediately() {
        trashDragActive = false;
        trashTargetHighlighted = false;
        if (trashTarget == null) {
            return;
        }
        trashTarget.animate().cancel();
        trashTarget.SetHighlighted(false);
        trashTarget.ResetAnimationState();
        trashTarget.setVisibility(View.GONE);
    }

    private void UpdateTrashTarget(boolean highlighted) {
        if (closingFromTrash) {
            return;
        }
        trashTargetHighlighted = highlighted;
        trashTarget.SetHighlighted(highlighted);
        trashTarget.animate().cancel();
        trashTarget.animate()
                .scaleX(highlighted ? 1.08f : 1f)
                .scaleY(highlighted ? 1.08f : 1f)
                .rotation(0f)
                .setDuration(highlighted ? 170 : 140)
                .setInterpolator(highlighted ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator())
                .start();
        if (highlighted) {
            AnimateBubbleCapturedByTrash();
            ShowChatHeadIcon(R.mipmap.closerec);
            chatHead.setBackground(CreatePreviewBorder(Color.rgb(232, 65, 72)));
        } else if (!settingsTargetHighlighted) {
            RestoreBubbleDragAppearance();
            RestoreBubbleAppearance();
        }
    }

    private void ShowSettingsTarget() {
        settingsTarget.animate().cancel();
        settingsTarget.ResetAnimationState();
        UpdateSettingsTarget(false);
        settingsTarget.setVisibility(View.VISIBLE);
        settingsTarget.setAlpha(0f);
        settingsTarget.setScaleX(0.72f);
        settingsTarget.setScaleY(0.72f);
        settingsTarget.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(210)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .start();
    }

    private void HideSettingsTarget() {
        UpdateSettingsTarget(false);
        settingsTarget.animate().cancel();
        settingsTarget.animate()
                .alpha(0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(130)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        settingsTarget.setVisibility(View.INVISIBLE);
                        settingsTarget.ResetAnimationState();
                    }
                })
                .start();
    }

    private void HideSettingsTargetImmediately() {
        settingsTargetHighlighted = false;
        if (settingsTarget == null) {
            return;
        }
        settingsTarget.animate().cancel();
        settingsTarget.SetHighlighted(false);
        settingsTarget.ResetAnimationState();
        settingsTarget.setVisibility(View.GONE);
    }

    private void UpdateSettingsTarget(boolean highlighted) {
        if (closingFromTrash) {
            return;
        }
        settingsTargetHighlighted = highlighted;
        settingsTarget.SetHighlighted(highlighted);
        settingsTarget.animate().cancel();
        settingsTarget.animate()
                .scaleX(highlighted ? 1.08f : 1f)
                .scaleY(highlighted ? 1.08f : 1f)
                .rotation(0f)
                .setDuration(highlighted ? 170 : 140)
                .setInterpolator(highlighted ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator())
                .start();
        if (highlighted) {
            AnimateBubbleCapturedByTrash();
            ShowChatHeadIcon(android.R.drawable.ic_menu_preferences);
            chatHead.setBackground(CreatePreviewBorder(Color.rgb(90, 160, 235)));
        } else if (!trashTargetHighlighted) {
            RestoreBubbleDragAppearance();
            RestoreBubbleAppearance();
        }
    }

    private void AnimateBubbleCapturedByTrash() {
        chatHead.animate().cancel();
        txtHead.animate().cancel();
        txtCameraBadge.animate().cancel();
        readyPreviewView.animate().cancel();
        chatHead.animate()
                .scaleX(0.76f)
                .scaleY(0.76f)
                .alpha(0.82f)
                .rotation(8f)
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        readyPreviewView.animate()
                .scaleX(0.76f)
                .scaleY(0.76f)
                .alpha(0.82f)
                .rotation(8f)
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        txtHead.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .alpha(0.72f)
                .setDuration(150)
                .start();
        txtCameraBadge.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .alpha(0.72f)
                .setDuration(150)
                .start();
    }

    private void RestoreBubbleDragAppearance() {
        RestoreAnimatedView(chatHead);
        RestoreAnimatedView(readyPreviewView);
        RestoreAnimatedView(txtHead);
        RestoreAnimatedView(txtCameraBadge);
    }

    private void RestoreAnimatedView(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .rotation(0f)
                .setDuration(140)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void RestoreBubbleAppearance() {
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            chatHead.setImageDrawable(null);
            if (DroidPrefsUtils.exibePreviaCamera(context)) {
                chatHead.setBackground(CreatePreviewBorder(Color.rgb(232, 65, 72)));
            } else {
                chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_RECORDING_PREVIEW_OFF));
            }
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            chatHead.setImageDrawable(null);
            if (DroidPrefsUtils.exibePreviaCamera(context)) {
                chatHead.setBackground(null);
            } else {
                chatHead.setBackground(CreatePreviewOffBubbleBackground(COLOR_READY_PREVIEW_OFF));
            }
        }
    }

    private void SetTouchTargetExpandedForResize(boolean expanded) {
        int touchSizeDp = expanded ? GetMaxChatHeadSizeDp() : GetTouchTargetSizeDp();
        touchParams.width = dp(touchSizeDp);
        touchParams.height = dp(touchSizeDp);
        touchParams.x = params.x;
        touchParams.y = params.y;
        try {
            windowManager.updateViewLayout(touchTarget, touchParams);
        } catch (Exception ex) {
            Log.d("DVR", ex.getMessage());
        }
    }

    private boolean IsPointerOverTrashTarget(MotionEvent event) {
        int[] location = new int[2];
        trashTarget.getLocationOnScreen(location);
        float centerX = location[0] + trashTarget.getWidth() / 2f;
        float centerY = location[1] + trashTarget.getHeight() / 2f;
        float distanceX = event.getRawX() - centerX;
        float distanceY = event.getRawY() - centerY;
        float radiusX = trashTarget.getWidth() / 2f + dp(24);
        float radiusY = trashTarget.getHeight() / 2f + dp(28);
        return (distanceX * distanceX) / (radiusX * radiusX)
                + (distanceY * distanceY) / (radiusY * radiusY) <= 1f;
    }

    private boolean IsPointerOverSettingsTarget(MotionEvent event) {
        int[] location = new int[2];
        settingsTarget.getLocationOnScreen(location);
        float centerX = location[0] + settingsTarget.getWidth() / 2f;
        float centerY = location[1] + settingsTarget.getHeight() / 2f;
        float distanceX = event.getRawX() - centerX;
        float distanceY = event.getRawY() - centerY;
        float radiusX = settingsTarget.getWidth() / 2f + dp(24);
        float radiusY = settingsTarget.getHeight() / 2f + dp(28);
        return (distanceX * distanceX) / (radiusX * radiusX)
                + (distanceY * distanceY) / (radiusY * radiusY) <= 1f;
    }

    private boolean CanUseDragTargets() {
        return DroidVideoRecorder.StateRecVideo != DroidConstants.EnumStateRecVideo.RECORD;
    }

    private void OpenSettingsFromTarget() {
        if (!CanUseDragTargets()) {
            HideTrashTargetImmediately();
            HideSettingsTargetImmediately();
            return;
        }

        HideTrashTarget();
        RestoreBubbleDragAppearance();
        RestoreBubbleAppearance();
        Vibrar(50);
        AbrirConfig();
    }

    private void CloseFromTrashTarget() {
        if (!CanUseDragTargets()) {
            HideTrashTargetImmediately();
            HideSettingsTargetImmediately();
            return;
        }

        if (closingFromTrash) {
            return;
        }
        closingFromTrash = true;
        trashDragActive = false;
        HideSettingsTarget();
        Fala(getString(R.string.fechando));
        PlayTrashExitSound();
        AnimateTrashExitAndStop();
    }

    private void PlayTrashExitSound() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack audioTrack = null;
                try {
                    int sampleRate = 44100;
                    int durationMs = 320;
                    int sampleCount = sampleRate * durationMs / 1000;
                    short[] pcm = new short[sampleCount];
                    double filteredNoise = 0d;
                    double tonePhase = 0d;

                    for (int i = 0; i < sampleCount; i++) {
                        double progress = i / (double) sampleCount;
                        double noise = DeterministicNoise(i);

                        filteredNoise = filteredNoise * 0.72d + noise * 0.28d;
                        double airEnvelope = Math.min(1d, progress / 0.08d) * Math.pow(1d - progress, 3.1d);

                        double toneStart = 0.36d;
                        double toneProgress = Math.max(0d, (progress - toneStart) / (1d - toneStart));
                        double toneFrequency = 156d - toneProgress * 48d;
                        tonePhase += 2d * Math.PI * toneFrequency / sampleRate;
                        double toneEnvelope = toneProgress > 0d
                                ? Math.sin(Math.PI * Math.min(toneProgress * 1.65d, 1d)) * Math.exp(-6.2d * toneProgress)
                                : 0d;
                        double softTone = Math.sin(tonePhase) * toneEnvelope;

                        double sample = filteredNoise * airEnvelope * 0.062d + softTone * 0.12d;
                        sample = Math.max(-1d, Math.min(1d, sample));
                        pcm[i] = (short) (sample * Short.MAX_VALUE);
                    }

                    audioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            pcm.length * 2,
                            AudioTrack.MODE_STATIC);
                    audioTrack.write(pcm, 0, pcm.length);
                    audioTrack.play();
                    Thread.sleep(durationMs + 120);
                } catch (Exception ex) {
                    Log.d("DVR", "Nao foi possivel tocar som da lixeira: " + ex.getMessage());
                } finally {
                    if (audioTrack != null) {
                        audioTrack.release();
                    }
                }
            }
        }).start();
    }

    private void PlayCameraSwitchSound() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack audioTrack = null;
                try {
                    int sampleRate = 44100;
                    int durationMs = 130;
                    int sampleCount = sampleRate * durationMs / 1000;
                    short[] pcm = new short[sampleCount];
                    double phase = 0d;

                    for (int i = 0; i < sampleCount; i++) {
                        double progress = i / (double) sampleCount;
                        double frequency = progress < 0.46d ? 820d : 1120d;
                        double attack = Math.min(1d, progress / 0.08d);
                        double release = Math.min(1d, (1d - progress) / 0.18d);
                        double envelope = attack * release;
                        phase += 2d * Math.PI * frequency / sampleRate;
                        double sample = Math.sin(phase) * envelope * 0.12d;
                        pcm[i] = (short) (sample * Short.MAX_VALUE);
                    }

                    audioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            pcm.length * 2,
                            AudioTrack.MODE_STATIC);
                    audioTrack.write(pcm, 0, pcm.length);
                    audioTrack.play();
                    Thread.sleep(durationMs + 60);
                } catch (Exception ex) {
                    Log.d("DVR", "Nao foi possivel tocar som da camera: " + ex.getMessage());
                } finally {
                    if (audioTrack != null) {
                        audioTrack.release();
                    }
                }
            }
        }).start();
    }

    private double DeterministicNoise(int seed) {
        double value = Math.sin(seed * 12.9898d + 78.233d) * 43758.5453d;
        return (value - Math.floor(value)) * 2d - 1d;
    }

    private void AnimateTrashExitAndStop() {
        trashTargetHighlighted = true;
        trashTarget.SetHighlighted(true);
        trashTarget.setVisibility(View.VISIBLE);
        trashTarget.setAlpha(1f);
        trashTarget.animate().cancel();
        trashTarget.animate()
                .scaleX(1.06f)
                .scaleY(1.06f)
                .rotation(0f)
                .setDuration(120)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();

        AnimateClosingView(chatHead, true);
        AnimateClosingView(readyPreviewView, false);
        AnimateClosingView(txtHead, false);
        AnimateClosingView(txtCameraBadge, false);
    }

    private void AnimateClosingView(View view, boolean finishAfterAnimation) {
        if (view == null) {
            if (finishAfterAnimation) {
                AnimateTrashEvaporationAndStop();
            }
            return;
        }

        int[] viewLocation = new int[2];
        int[] trashLocation = new int[2];
        view.getLocationOnScreen(viewLocation);
        trashTarget.getLocationOnScreen(trashLocation);
        float viewCenterX = viewLocation[0] + view.getWidth() / 2f;
        float viewCenterY = viewLocation[1] + view.getHeight() / 2f;
        float trashCenterX = trashLocation[0] + trashTarget.getWidth() / 2f;
        float trashCenterY = trashLocation[1] + trashTarget.getHeight() / 2f;

        view.animate().cancel();
        view.animate()
                .translationX(trashCenterX - viewCenterX)
                .translationY(trashCenterY - viewCenterY)
                .scaleX(0.08f)
                .scaleY(0.08f)
                .alpha(0f)
                .rotation(34f)
                .setDuration(680)
                .setInterpolator(new AccelerateInterpolator())
                .setListener(finishAfterAnimation ? new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        AnimateTrashEvaporationAndStop();
                    }
                } : null)
                .start();
    }

    private void AnimateTrashEvaporationAndStop() {
        trashTarget.SetClosingOnlyTrash(true);
        trashTarget.animate().cancel();
        trashTarget.animate()
                .scaleX(1.32f)
                .scaleY(1.13f)
                .alpha(1f)
                .rotation(0f)
                .setDuration(360)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        BeginTrashEvaporationAndStop();
                    }
                })
                .start();
    }

    private void BeginTrashEvaporationAndStop() {
        final ValueAnimator evaporation = ValueAnimator.ofFloat(0f, 1f);
        evaporation.setDuration(760);
        evaporation.setInterpolator(new DecelerateInterpolator());
        evaporation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                trashTarget.SetEvaporationProgress((Float) animation.getAnimatedValue());
            }
        });
        evaporation.start();

        trashTarget.animate().cancel();
        trashTarget.animate()
                .scaleX(1.72f)
                .scaleY(1.42f)
                .alpha(0f)
                .rotation(0f)
                .setDuration(760)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        trashTarget.StopPulse();
                        StopService();
                    }
                })
                .start();
    }

    private void ShowChatHeadIcon(int resourceId) {
        chatHead.setBackground(null);
        chatHead.setImageResource(resourceId);
    }

    private void StartReadyPreview(DroidConstants.EnumTypeViewCam typeViewCam) {
        pendingReadyPreview = true;
        pendingPreviewCam = typeViewCam;
        StartPendingReadyPreview();
    }

    private void StartPendingReadyPreview() {
        if (!pendingReadyPreview || readyPreviewView == null || !readyPreviewView.isAvailable()) {
            return;
        }

        pendingReadyPreview = false;
        DroidVideoRecorder.OnInitRec(getResources().getConfiguration(), orientationEvent, pendingPreviewCam);
        ApplyPreviewTransform();
        DroidVideoRecorder.OnViewRec(readyPreviewView.getSurfaceTexture());
    }

    private void StartPreview(DroidConstants.EnumTypeViewCam typeViewCam) {
        pendingPreview = true;
        pendingPreviewCam = typeViewCam;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                StartPendingPreview();
            }
        }, 300);
    }

    private void StartPendingPreview() {
        if (!pendingPreview || mSurfaceView == null || !mSurfaceView.getHolder().getSurface().isValid()) {
            return;
        }

        pendingPreview = false;
        DroidVideoRecorder.OnInitRec(getResources().getConfiguration(), orientationEvent, pendingPreviewCam);
        DroidVideoRecorder.OnViewRec(mSurfaceView.getHolder());
    }

    private void ShowClose() {
        HideCameraIndicator();
        HideRecordingBadge();
        HideReadyPreview();
        ShowChatHeadIcon(R.mipmap.closerec);
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.CLOSE;
        UpdateNotification(getString(R.string.notification_tap_to_exit));
        Vibrar(50);
    }

    private void ShowActivity() {
        //context = getBaseContext();
        Intent mItent = new Intent(context, DroidConfigurationActivity.class);

        mItent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mItent.putExtra(DroidConstants.CHAMADAPELOSERVICO, true);
        mItent.putExtra(DroidConfigurationActivity.EXTRA_RESTORE_SERVICE_ON_CLOSE, true);
        startActivity(mItent);
    }

    private boolean Permite(DroidConstants.EnumStateRecVideo stateRecVideo) {
        if (videoProcessingActive) {
            return false;
        }

        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            return stateRecVideoSTOP.contains(stateRecVideo);
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
            return stateRecVideoVIEW.contains(stateRecVideo);
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            return stateRecVideoREC.contains(stateRecVideo);
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.CLOSE) {
            return stateRecVideoCLOSE.contains(stateRecVideo);
        } else return false;
    }

    private void Vibrar(int valor) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(valor);
        } catch (Exception ex) {
        }
    }

    private class Sincronizar extends AsyncTask<Void, Integer, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            try {
                int minutes = 0;
                int second = 0;
                while (second <= 60) {
                    Thread.sleep(1000);
                    second++;
                    if (second == 60) {
                        minutes++;
                        second = 0;
                    }
                    publishProgress(second, minutes);

                }
            } catch (Exception e) {
            }
            return null;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            ShowRecordingTimer();

        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            txtHead.setText("00:00");
            if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
                HideCameraIndicator();
            } else {
                txtHead.setVisibility(View.INVISIBLE);
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);
            DecimalFormat df = new DecimalFormat("00");
            txtHead.setText(df.format(values[1]) + ":" + df.format(values[0]));
        }
    }

    private class SettingsTargetView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private boolean highlighted;
        private float highlightPulse;
        private ValueAnimator pulseAnimator;

        public SettingsTargetView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setWillNotDraw(false);
        }

        public void SetHighlighted(boolean highlighted) {
            if (this.highlighted == highlighted) {
                invalidate();
                return;
            }
            this.highlighted = highlighted;
            if (highlighted) {
                StartPulse();
            } else {
                StopPulse();
            }
            invalidate();
        }

        public void ResetAnimationState() {
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            setRotation(0f);
            StopPulse();
            SetHighlighted(false);
        }

        private void StartPulse() {
            StopPulse();
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(1450);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setInterpolator(new LinearInterpolator());
            pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    highlightPulse = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            pulseAnimator.start();
        }

        private void StopPulse() {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            highlightPulse = 0f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height / 2f;

            DrawTargetCard(canvas, width, height);
            DrawGearIcon(canvas, centerX, centerY);
        }

        private void DrawTargetCard(Canvas canvas, float width, float height) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(highlighted ? 220 : 190, 30, 33, 39));
            paint.setShadowLayer(dp(highlighted ? 18 : 11), 0, dp(6),
                    Color.argb(highlighted ? 145 : 100, 0, 0, 0));
            rect.set(dp(9), dp(9), width - dp(9), height - dp(9));
            canvas.drawRoundRect(rect, dp(28), dp(28), paint);

            paint.clearShadowLayer();
            paint.setShader(new LinearGradient(
                    0f,
                    rect.top,
                    0f,
                    rect.bottom,
                    new int[]{
                            Color.argb(70, 255, 255, 255),
                            Color.argb(12, 255, 255, 255),
                            Color.argb(48, 0, 0, 0)
                    },
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, dp(28), dp(28), paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(highlighted ? 2 : 1));
            paint.setColor(highlighted
                    ? Color.argb(180, 115, 180, 255)
                    : Color.argb(95, 245, 248, 252));
            canvas.drawRoundRect(rect, dp(26), dp(26), paint);
        }

        private void DrawGearIcon(Canvas canvas, float centerX, float centerY) {
            float rotation = highlighted ? highlightPulse * 360f : 0f;
            float outerRadius = dp(29);
            float innerRadius = dp(22);

            canvas.save();
            canvas.rotate(rotation, centerX, centerY);
            path.reset();
            for (int i = 0; i < 24; i++) {
                double angle = -Math.PI / 2d + i * Math.PI * 2d / 24d;
                float radius = i % 3 == 1 ? innerRadius : outerRadius;
                float x = centerX + (float) Math.cos(angle) * radius;
                float y = centerY + (float) Math.sin(angle) * radius;
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            path.close();

            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(224, 231, 239));
            canvas.drawPath(path, paint);

            paint.setColor(Color.rgb(45, 50, 58));
            canvas.drawCircle(centerX, centerY, dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(150, 255, 255, 255));
            canvas.drawCircle(centerX, centerY, dp(11), paint);
            canvas.restore();
        }

    }

    private class TrashTargetView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private boolean highlighted;
        private float evaporationProgress;
        private float highlightPulse;
        private boolean closingOnlyTrash;
        private ValueAnimator pulseAnimator;

        public TrashTargetView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setWillNotDraw(false);
        }

        public void SetHighlighted(boolean highlighted) {
            if (this.highlighted == highlighted) {
                invalidate();
                return;
            }
            this.highlighted = highlighted;
            if (highlighted) {
                StartPulse();
            } else {
                StopPulse();
            }
            invalidate();
        }

        public void SetEvaporationProgress(float evaporationProgress) {
            this.evaporationProgress = evaporationProgress;
            invalidate();
        }

        public void SetClosingOnlyTrash(boolean closingOnlyTrash) {
            this.closingOnlyTrash = closingOnlyTrash;
            invalidate();
        }

        public void ResetAnimationState() {
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            setRotation(0f);
            StopPulse();
            SetClosingOnlyTrash(false);
            SetEvaporationProgress(0f);
            SetHighlighted(false);
        }

        private void StartPulse() {
            StopPulse();
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(920);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setInterpolator(new LinearInterpolator());
            pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    highlightPulse = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            pulseAnimator.start();
        }

        private void StopPulse() {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            highlightPulse = 0f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height / 2f + dp(3);
            float alphaMultiplier = 1f - evaporationProgress;

            if (!closingOnlyTrash) {
                DrawTargetCard(canvas, width, height, alphaMultiplier);
            }
            if (evaporationProgress < 0.78f) {
                DrawTrashIcon(canvas, centerX, centerY, alphaMultiplier);
            }
            DrawSubtleHoverDust(canvas, width, centerX, centerY);
            DrawDissolveParticles(canvas, width, height, centerX, centerY);
        }

        private void DrawTargetCard(Canvas canvas, float width, float height, float alphaMultiplier) {
            int alpha = Math.max(0, Math.min(255, (int) (255 * alphaMultiplier)));

            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) ((highlighted ? 220 : 190) * alphaMultiplier), 30, 33, 39));
            paint.setShadowLayer(dp(highlighted ? 18 : 11), 0, dp(6),
                    Color.argb((int) ((highlighted ? 145 : 100) * alphaMultiplier), 0, 0, 0));
            rect.set(dp(9), dp(9), width - dp(9), height - dp(9));
            canvas.drawRoundRect(rect, dp(28), dp(28), paint);

            paint.clearShadowLayer();
            paint.setShader(new LinearGradient(
                    0f,
                    rect.top,
                    0f,
                    rect.bottom,
                    new int[]{
                            Color.argb((int) (70 * alphaMultiplier), 255, 255, 255),
                            Color.argb((int) (12 * alphaMultiplier), 255, 255, 255),
                            Color.argb((int) (48 * alphaMultiplier), 0, 0, 0)
                    },
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, dp(28), dp(28), paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb((int) ((highlighted ? 155 : 95) * alphaMultiplier), 245, 248, 252));
            canvas.drawRoundRect(rect, dp(26), dp(26), paint);

            if (highlighted) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Color.argb((int) (95 * alphaMultiplier), 120, 170, 255));
                rect.set(dp(13), dp(13), width - dp(13), height - dp(13));
                canvas.drawRoundRect(rect, dp(24), dp(24), paint);
            }
        }

        private void DrawTrashIcon(Canvas canvas, float centerX, float centerY, float alphaMultiplier) {
            int alpha = Math.max(0, Math.min(255, (int) (255 * alphaMultiplier)));
            float bodyTop = centerY - dp(1);
            float bodyBottom = centerY + dp(35);
            float bodyLeft = centerX - dp(24);
            float bodyRight = centerX + dp(24);

            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    bodyLeft,
                    bodyTop,
                    bodyRight,
                    bodyBottom,
                    new int[]{
                            Color.argb(alpha, 242, 245, 248),
                            Color.argb(alpha, 184, 194, 204),
                            Color.argb(alpha, 238, 241, 245)
                    },
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            path.reset();
            path.moveTo(bodyLeft + dp(5), bodyTop);
            path.lineTo(bodyRight - dp(5), bodyTop);
            path.lineTo(bodyRight - dp(10), bodyBottom);
            path.lineTo(bodyLeft + dp(10), bodyBottom);
            path.close();
            canvas.drawPath(path, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.argb((int) (145 * alphaMultiplier), 255, 255, 255));
            canvas.drawLine(centerX - dp(12), bodyTop + dp(8), centerX - dp(9), bodyBottom - dp(8), paint);
            canvas.drawLine(centerX, bodyTop + dp(7), centerX, bodyBottom - dp(7), paint);
            canvas.drawLine(centerX + dp(12), bodyTop + dp(8), centerX + dp(9), bodyBottom - dp(8), paint);

            paint.setColor(Color.argb((int) (80 * alphaMultiplier), 35, 39, 45));
            canvas.drawLine(bodyLeft + dp(8), bodyBottom, bodyRight - dp(8), bodyBottom, paint);

            float lidY = centerY - dp(16);
            float lidPivotX = centerX - dp(28);
            float lidPivotY = lidY + dp(6);
            float openAngle = highlighted ? -42f - highlightPulse * 6f : -2f;
            canvas.save();
            canvas.rotate(openAngle, lidPivotX, lidPivotY);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    centerX - dp(30),
                    lidY - dp(5),
                    centerX + dp(30),
                    lidY + dp(8),
                    Color.argb(alpha, 250, 252, 255),
                    Color.argb(alpha, 178, 188, 199),
                    Shader.TileMode.CLAMP));
            rect.set(centerX - dp(31), lidY - dp(4), centerX + dp(31), lidY + dp(7));
            canvas.drawRoundRect(rect, dp(5), dp(5), paint);
            paint.setShader(null);

            paint.setColor(Color.argb(alpha, 238, 242, 246));
            rect.set(centerX - dp(10), lidY - dp(13), centerX + dp(10), lidY - dp(6));
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            canvas.restore();
        }

        private void DrawSubtleHoverDust(Canvas canvas, float width, float centerX, float centerY) {
            if (!highlighted || evaporationProgress > 0f) {
                return;
            }
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            int[] colors = new int[]{
                    Color.rgb(245, 249, 252),
                    Color.rgb(180, 215, 255),
                    Color.rgb(222, 233, 242)
            };
            float mouthX = centerX;
            float mouthY = centerY - dp(17);
            for (int i = 0; i < 22; i++) {
                float seed = i / 21f;
                float phase = (highlightPulse + seed * 0.74f) % 1f;
                float side = i % 2 == 0 ? -1f : 1f;
                float startX = centerX + side * (width * (0.50f + (i % 5) * 0.035f));
                float startY = centerY - dp(52) + (i % 6) * dp(11);
                float curve = (float) Math.sin(phase * Math.PI);
                float x = startX + (mouthX - startX) * phase + side * curve * dp(8);
                float y = startY + (mouthY - startY) * phase - curve * dp(12);
                float size = dp(2 + (i % 3)) * (1.35f - phase * 0.45f);
                int alpha = Math.max(40, (int) (230 * (1f - phase * 0.42f)));
                if (i % 3 == 0) {
                    DrawSpark(canvas, x, y, size + dp(2), colors[i % colors.length], alpha);
                } else {
                    DrawDust(canvas, x, y, size, colors[i % colors.length], alpha);
                }
            }
        }

        private void DrawDissolveParticles(Canvas canvas, float width, float height, float centerX, float centerY) {
            if (evaporationProgress <= 0f) {
                return;
            }
            int[] colors = new int[]{
                    Color.rgb(236, 240, 244),
                    Color.rgb(170, 205, 246),
                    Color.rgb(214, 222, 232)
            };
            for (int i = 0; i < 28; i++) {
                float seed = i / 27f;
                float angle = (float) (seed * Math.PI * 2.2f + i * 0.41f);
                float distance = dp(8) + evaporationProgress * dp(48 + (i % 7) * 5);
                float arc = (float) Math.sin(evaporationProgress * Math.PI);
                float x = centerX + (float) Math.cos(angle) * distance * 0.72f;
                float y = centerY + dp(8)
                        + (float) Math.sin(angle) * distance * 0.38f
                        - evaporationProgress * dp(44)
                        - arc * dp(12);
                int alpha = Math.max(0, (int) (220 * (1f - evaporationProgress * 0.86f)));
                if (i % 4 == 0) {
                    DrawSpark(canvas, x, y, dp(3), colors[i % colors.length], alpha);
                } else {
                    DrawDust(canvas, x, y, dp(2), colors[i % colors.length], alpha);
                }
            }
        }

        private void DrawDust(Canvas canvas, float centerX, float centerY, float size, int color, int alpha) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(centerX, centerY, Math.max(1f, size), paint);
        }

        private void DrawSpark(Canvas canvas, float centerX, float centerY, float size, int color, int alpha) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, size * 0.38f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawLine(centerX - size, centerY, centerX + size, centerY, paint);
            canvas.drawLine(centerX, centerY - size, centerX, centerY + size, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.min(255, alpha + 25), Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(centerX, centerY, Math.max(1f, size * 0.28f), paint);
        }
    }

    public class TouchListener implements View.OnTouchListener {

        private final int touchSlop = ViewConfiguration.get(DroidHeadService.this).getScaledTouchSlop();
        private boolean dragStarted;
        private boolean multiTouchGesture;
        private boolean singleFingerGestureAccepted;
        private int scaleStartSizeDp;
        private float scaleStartSpan;

        private GestureDetector gestureDetector = new GestureDetector(DroidHeadService.this, new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onDoubleTap(MotionEvent e) {

                if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
                    Visualizar();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
                    VisualizarTrocandoCamera();
                }

                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (!dragStarted
                        && (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP
                        || DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW)) {
                    VisualizarTrocandoCamera();
                }
                super.onLongPress(e);
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {

                if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
                    Gravar();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
                    Parar();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
                    Gravar();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.CLOSE) {
                    Sair();
                }

                return super.onSingleTapConfirmed(e);
            }
        });
        private boolean manualScaleGesture;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (videoProcessingActive) {
                return true;
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
                multiTouchGesture = true;
                dragStarted = false;
                if (trashDragActive && !closingFromTrash) {
                    HideTrashTarget();
                }
                CancelSingleFingerGesture(event);
                BeginManualScale(event);
            }

            if (multiTouchGesture) {
                if (action == MotionEvent.ACTION_MOVE && manualScaleGesture) {
                    UpdateManualScale(event);
                }
                if (action == MotionEvent.ACTION_POINTER_UP
                        || action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    EndManualScale();
                    multiTouchGesture = false;
                    singleFingerGestureAccepted = false;
                }
                return true;
            }

            if (action == MotionEvent.ACTION_DOWN) {
                singleFingerGestureAccepted = IsPointerNearVisibleBubble(event);
                if (!singleFingerGestureAccepted) {
                    return false;
                }
            } else if (!singleFingerGestureAccepted) {
                return false;
            }

            gestureDetector.onTouchEvent(event);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    dragStarted = false;
                    if (myOrientationEventListener.canDetectOrientation()) {
                        myOrientationEventListener.enable();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    boolean canUseDragTargets = CanUseDragTargets();
                    Integer totalMoveX = (int) (event.getRawX() - initialTouchX);
                    params.x = initialX + totalMoveX;
                    Integer totalMoveY = (int) (event.getRawY() - initialTouchY);
                    params.y = initialY + totalMoveY;
                    touchParams.x = params.x;
                    touchParams.y = params.y;
                    if (!dragStarted && IsDragGesture(totalMoveX, totalMoveY)) {
                        dragStarted = true;
                        if (canUseDragTargets) {
                            ShowTrashTarget();
                        }
                    }
                    windowManager.updateViewLayout(chatHead, params);
                    windowManager.updateViewLayout(txtHead, params);
                    windowManager.updateViewLayout(txtCameraBadge, params);
                    windowManager.updateViewLayout(touchTarget, touchParams);
                    if ((DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP
                            || DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD)
                            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        readyPreviewParams.x = params.x;
                        readyPreviewParams.y = params.y;
                        windowManager.updateViewLayout(readyPreviewView, readyPreviewParams);
                    }
                    if (trashDragActive && canUseDragTargets) {
                        boolean pointerOverSettingsTarget = IsPointerOverSettingsTarget(event);
                        boolean pointerOverTrashTarget = IsPointerOverTrashTarget(event);
                        if (pointerOverSettingsTarget) {
                            pointerOverTrashTarget = false;
                        }
                        if (pointerOverTrashTarget != trashTargetHighlighted) {
                            UpdateTrashTarget(pointerOverTrashTarget);
                        }
                        if (pointerOverSettingsTarget != settingsTargetHighlighted) {
                            UpdateSettingsTarget(pointerOverSettingsTarget);
                        }
                    } else if (trashDragActive) {
                        HideTrashTargetImmediately();
                        HideSettingsTargetImmediately();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (trashDragActive) {
                        if (!CanUseDragTargets()) {
                            HideTrashTargetImmediately();
                            HideSettingsTargetImmediately();
                            singleFingerGestureAccepted = false;
                            return true;
                        }

                        boolean openSettings = settingsTargetHighlighted;
                        boolean closeApp = trashTargetHighlighted;
                        if (openSettings) {
                            OpenSettingsFromTarget();
                        } else if (closeApp) {
                            CloseFromTrashTarget();
                        } else {
                            HideTrashTarget();
                        }
                    }
                    singleFingerGestureAccepted = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (trashDragActive && !closingFromTrash) {
                        HideTrashTarget();
                    }
                    singleFingerGestureAccepted = false;
                    return true;
            }

            return true;
        }

        private boolean IsDragGesture(int totalMoveX, int totalMoveY) {
            return totalMoveX * totalMoveX + totalMoveY * totalMoveY >= touchSlop * touchSlop;
        }

        private boolean CanResizeBubble() {
            return DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP
                    && !palmCountdownActive
                    && !trashDragActive;
        }

        private void BeginManualScale(MotionEvent event) {
            if (!CanResizeBubble() || event.getPointerCount() < 2) {
                manualScaleGesture = false;
                return;
            }

            manualScaleGesture = true;
            scaleStartSizeDp = chatHeadSizeDp;
            scaleStartSpan = Math.max(1f, GetPointerSpan(event));
            ShowResizeBubbleIndicator();
        }

        private void UpdateManualScale(MotionEvent event) {
            if (event.getPointerCount() < 2 || scaleStartSpan <= 0f) {
                return;
            }

            float scaleRatio = GetPointerSpan(event) / scaleStartSpan;
            float normalizedMove = scaleRatio >= 1f
                    ? (scaleRatio - 1f) / CHAT_HEAD_FULL_PINCH_RANGE
                    : -(1f - scaleRatio) / CHAT_HEAD_FULL_PINCH_RANGE;
            int scaledPercent = Math.round(GetBubbleSizePercent(scaleStartSizeDp) + normalizedMove * 99f);
            int scaledSize = GetBubbleSizeFromPercent(scaledPercent);
            ResizeReadyBubble(scaledSize, false);
            UpdateResizeBubbleIndicator(scaledSize);
        }

        private void EndManualScale() {
            if (manualScaleGesture) {
                DroidPrefsUtils.salvaTamanhoBolinha(context, chatHeadSizeDp);
            }
            manualScaleGesture = false;
            scaleStartSizeDp = 0;
            scaleStartSpan = 0f;
            HideResizeBubbleIndicator();
        }

        private float GetPointerSpan(MotionEvent event) {
            if (event.getPointerCount() < 2) {
                return 0f;
            }

            float distanceX = event.getX(1) - event.getX(0);
            float distanceY = event.getY(1) - event.getY(0);
            return (float) Math.sqrt(distanceX * distanceX + distanceY * distanceY);
        }

        private boolean IsPointerNearVisibleBubble(MotionEvent event) {
            int[] location = new int[2];
            chatHead.getLocationOnScreen(location);
            float centerX = location[0] + chatHead.getWidth() / 2f;
            float centerY = location[1] + chatHead.getHeight() / 2f;
            float radius = chatHead.getWidth() / 2f + dp(16);
            float distanceX = event.getRawX() - centerX;
            float distanceY = event.getRawY() - centerY;
            return distanceX * distanceX + distanceY * distanceY <= radius * radius;
        }

        private void CancelSingleFingerGesture(MotionEvent event) {
            MotionEvent cancelEvent = MotionEvent.obtain(event);
            cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
            gestureDetector.onTouchEvent(cancelEvent);
            cancelEvent.recycle();
        }

    }

}






