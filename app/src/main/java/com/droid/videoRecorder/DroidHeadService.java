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
    private static final int CHAT_HEAD_MAX_SIZE_DP = 220;
    private static final int CHAT_HEAD_MIN_TOUCH_SIZE_DP = 148;
    private static final int TRASH_TARGET_WIDTH_DP = 104;
    private static final int TRASH_TARGET_HEIGHT_DP = 108;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PalmGestureDetector palmGestureDetector;
    private boolean palmCountdownActive;
    private int palmCountdownValue;
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

    private int BubbleDp(int baseValue) {
        return Math.max(1, Math.round(baseValue * chatHeadSizeDp / (float) CHAT_HEAD_DEFAULT_SIZE_DP));
    }

    private float BubbleTextSize(float baseValue) {
        return Math.max(1f, baseValue * chatHeadSizeDp / CHAT_HEAD_DEFAULT_SIZE_DP);
    }

    private int GetTouchTargetSizeDp() {
        return Math.max(chatHeadSizeDp, CHAT_HEAD_MIN_TOUCH_SIZE_DP);
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
        serviceActive = false;
        if (activeService.get() == this) {
            activeService.clear();
        }
        super.onDestroy();
        DroidVideoRecorder.OnStopRecording(false);
        if (touchTarget != null) windowManager.removeView(touchTarget);
        if (chatHead != null) windowManager.removeView(chatHead);
        if (txtHead != null) windowManager.removeView(txtHead);
        if (txtCameraBadge != null) windowManager.removeView(txtCameraBadge);
        if (mSurfaceView != null) windowManager.removeView(mSurfaceView);
        if (readyPreviewView != null) windowManager.removeView(readyPreviewView);
        if (trashTarget != null) windowManager.removeView(trashTarget);
        if (settingsTarget != null) windowManager.removeView(settingsTarget);
        if (palmGestureDetector != null) palmGestureDetector.Close();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        Vibrar(100);
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
        txtHead.setText(GetCameraIndicatorText());
        txtHead.setTextSize(BubbleTextSize(10));
        txtHead.setGravity(Gravity.CENTER);
        txtHead.setPadding(0, 0, 0, 0);
        txtHead.setVisibility(View.VISIBLE);
        HideRecordingBadge();
    }

    private void HideCameraIndicator() {
        txtHead.setVisibility(View.INVISIBLE);
    }

    private void ShowRecordingBadge() {
        txtCameraBadge.setText(GetCameraBadgeText());
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
                CHAT_HEAD_MAX_SIZE_DP);

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
        stopSelf();
        tts.shutdown();
    }

    public static void SetHiddenByReview(boolean hidden) {
        DroidHeadService service = activeService.get();
        if (service != null) {
            service.mainHandler.post(() -> service.SetHiddenByReviewInternal(hidden));
        }
    }

    private void SetHiddenByReviewInternal(boolean hidden) {
        if (hidden) {
            HideReadyPreview();
            if (chatHead != null) chatHead.setVisibility(View.INVISIBLE);
            if (txtHead != null) txtHead.setVisibility(View.INVISIBLE);
            if (txtCameraBadge != null) txtCameraBadge.setVisibility(View.INVISIBLE);
            if (touchTarget != null) touchTarget.setVisibility(View.INVISIBLE);
            return;
        }

        if (chatHead != null) chatHead.setVisibility(View.VISIBLE);
        if (touchTarget != null) touchTarget.setVisibility(View.VISIBLE);
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            ShowReadyPreview();
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            ShowRecordingPreview();
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
        ShowRecordingPreview();
        SetPreviewFullScreen(false);
        DroidVideoRecorder.OnInitRec(getResources().getConfiguration(), orientationEvent, DroidVideoRecorder.TypeViewCam);
        ApplyPreviewTransform();
        boolean recordingStarted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && readyPreviewView != null
                && readyPreviewView.isAvailable()) {
            recordingStarted = DroidVideoRecorder.OnStartRecording(
                    readyPreviewView.getSurfaceTexture(),
                    orientationEvent);
        } else {
            recordingStarted = DroidVideoRecorder.OnStartRecording(mSurfaceView.getHolder(), orientationEvent);
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
                shouldReviewVideo ? video -> mainHandler.post(() -> OpenVideoReview(video)) : null);
        if (record && asyncTask != null) {
            asyncTask.cancel(true);
        }
        ShowStop();
        if (shouldReviewVideo && recordedVideo != null) {
            OpenVideoReview(recordedVideo);
        }
        Vibrar(50);
    }

    private void OpenVideoReview(DroidVideoRecorder.RecordedVideo video) {
        if (video == null || !video.HasVideo()) {
            return;
        }

        Intent intent = DroidVideoReviewActivity.CreateIntent(context, video);
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
        SetPreviewFullScreen(false);
        DroidVideoRecorder.StateRecVideo = DroidConstants.EnumStateRecVideo.STOP;
        UpdateNotification(GetReadyNotificationText());
        ShowReadyPreview();
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

        chatHead.setImageDrawable(null);
        chatHead.setBackground(CreatePreviewBorder(Color.rgb(49, 184, 96)));
        HideCameraIndicator();
        SetPreviewBubble();
        StartReadyPreview(DroidVideoRecorder.TypeViewCam);
        StartPalmGestureDetection();
    }

    private void ShowRecordingPreview() {
        PausePalmGestureDetection();
        pendingReadyPreview = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            ShowChatHeadIcon(R.mipmap.rec);
            return;
        }

        chatHead.setImageDrawable(null);
        chatHead.setBackground(CreatePreviewBorder(Color.rgb(232, 65, 72)));
        SetPreviewBubble();
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
            readyPreviewView.setVisibility(View.VISIBLE);
            windowManager.updateViewLayout(readyPreviewView, readyPreviewParams);
            ApplyPreviewTransform();
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

    private void ResizeReadyBubble(int newSizeDp, boolean persist) {
        if (DroidVideoRecorder.StateRecVideo != DroidConstants.EnumStateRecVideo.STOP) {
            return;
        }

        chatHeadSizeDp = Math.max(CHAT_HEAD_MIN_SIZE_DP, Math.min(CHAT_HEAD_MAX_SIZE_DP, newSizeDp));
        params.width = dp(chatHeadSizeDp);
        params.height = dp(chatHeadSizeDp);
        touchParams.width = dp(GetTouchTargetSizeDp());
        touchParams.height = dp(GetTouchTargetSizeDp());
        touchParams.x = params.x;
        touchParams.y = params.y;
        readyPreviewParams.width = dp(chatHeadSizeDp);
        readyPreviewParams.height = dp(chatHeadSizeDp);
        txtCameraBadge.setPadding(0, 0, 0, dp(BubbleDp(5)));
        txtCameraBadge.setTextSize(BubbleTextSize(9));
        chatHead.setBackground(CreatePreviewBorder(Color.rgb(49, 184, 96)));

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
        if (!palmCountdownActive && palmGestureDetector != null) {
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
            chatHead.setBackground(CreatePreviewBorder(Color.rgb(232, 65, 72)));
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            chatHead.setImageDrawable(null);
            chatHead.setBackground(CreatePreviewBorder(Color.rgb(49, 184, 96)));
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

    private void OpenSettingsFromTarget() {
        HideTrashTarget();
        RestoreBubbleDragAppearance();
        RestoreBubbleAppearance();
        Vibrar(50);
        AbrirConfig();
    }

    private void CloseFromTrashTarget() {
        if (closingFromTrash) {
            return;
        }
        closingFromTrash = true;
        trashDragActive = false;
        HideSettingsTarget();
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            Fala(getString(R.string.parandoGravacao));
            ShowStopRecord(true);
        } else {
            Fala(getString(R.string.fechando));
        }
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
                .scaleX(1.12f)
                .scaleY(1.12f)
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
                .setDuration(430)
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
        final ValueAnimator evaporation = ValueAnimator.ofFloat(0f, 1f);
        evaporation.setDuration(520);
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
                .scaleX(1.18f)
                .scaleY(1.18f)
                .alpha(0f)
                .rotation(0f)
                .setDuration(520)
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
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                StartPendingReadyPreview();
            }
        }, 300);
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
        startActivity(mItent);
    }

    private boolean Permite(DroidConstants.EnumStateRecVideo stateRecVideo) {

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
            float rotation = highlighted ? highlightPulse * 28f : 0f;
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
            paint.setColor(highlighted
                    ? Color.rgb(132, 188, 255)
                    : Color.rgb(224, 231, 239));
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

        public void ResetAnimationState() {
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            setRotation(0f);
            StopPulse();
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

            DrawTargetCard(canvas, width, height, alphaMultiplier);
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
            float openAngle = highlighted ? -30f - highlightPulse * 4f : -2f;
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
            int color = Color.rgb(236, 241, 247);
            for (int i = 0; i < 4; i++) {
                float seed = i / 3f;
                float wave = (float) Math.sin((highlightPulse + seed) * Math.PI * 2f);
                float x = centerX + (seed - 0.5f) * width * 0.36f + wave * dp(2);
                float y = centerY - dp(42 + i % 2 * 5) - highlightPulse * dp(4);
                DrawDust(canvas, x, y, dp(1), color, 95);
            }
        }

        private void DrawDissolveParticles(Canvas canvas, float width, float height, float centerX, float centerY) {
            if (evaporationProgress <= 0f) {
                return;
            }
            int[] colors = new int[]{
                    Color.rgb(236, 240, 244),
                    Color.rgb(184, 196, 208),
                    Color.rgb(214, 160, 150)
            };
            for (int i = 0; i < 16; i++) {
                float seed = i / 15f;
                float angle = (float) (seed * Math.PI * 2.2f + i * 0.41f);
                float distance = dp(8) + evaporationProgress * dp(38 + (i % 6) * 4);
                float arc = (float) Math.sin(evaporationProgress * Math.PI);
                float x = centerX + (float) Math.cos(angle) * distance * 0.72f;
                float y = centerY + dp(8)
                        + (float) Math.sin(angle) * distance * 0.38f
                        - evaporationProgress * dp(36)
                        - arc * dp(8);
                int alpha = Math.max(0, (int) (185 * (1f - evaporationProgress * 0.86f)));
                DrawDust(canvas, x, y, dp(2), colors[i % colors.length], alpha);
            }
        }

        private void DrawDust(Canvas canvas, float centerX, float centerY, float size, int color, int alpha) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(centerX, centerY, Math.max(1f, size), paint);
        }
    }

    public class TouchListener implements View.OnTouchListener {

        private final int touchSlop = ViewConfiguration.get(DroidHeadService.this).getScaledTouchSlop();
        private boolean dragStarted;
        private boolean multiTouchGesture;
        private boolean singleFingerGestureAccepted;

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
        private ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(
                DroidHeadService.this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        return DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP
                                && !palmCountdownActive
                                && !trashDragActive;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        int scaledSize = Math.round(chatHeadSizeDp * detector.getScaleFactor());
                        ResizeReadyBubble(scaledSize, false);
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        DroidPrefsUtils.salvaTamanhoBolinha(context, chatHeadSizeDp);
                    }
                });

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
                multiTouchGesture = true;
                dragStarted = false;
                if (trashDragActive && !closingFromTrash) {
                    HideTrashTarget();
                }
                CancelSingleFingerGesture(event);
            }

            scaleGestureDetector.onTouchEvent(event);
            if (multiTouchGesture) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    multiTouchGesture = false;
                    singleFingerGestureAccepted = false;
                }
                return true;
            }

            if (action == MotionEvent.ACTION_DOWN) {
                singleFingerGestureAccepted = IsPointerNearVisibleBubble(event);
                if (!singleFingerGestureAccepted) {
                    return true;
                }
            } else if (!singleFingerGestureAccepted) {
                return true;
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
                    Integer totalMoveX = (int) (event.getRawX() - initialTouchX);
                    params.x = initialX + totalMoveX;
                    Integer totalMoveY = (int) (event.getRawY() - initialTouchY);
                    params.y = initialY + totalMoveY;
                    touchParams.x = params.x;
                    touchParams.y = params.y;
                    if (!dragStarted && IsDragGesture(totalMoveX, totalMoveY)) {
                        dragStarted = true;
                        ShowTrashTarget();
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
                    if (trashDragActive) {
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
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (trashDragActive) {
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






