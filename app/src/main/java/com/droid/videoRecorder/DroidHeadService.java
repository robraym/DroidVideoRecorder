package com.droid.videoRecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Locale;


public class DroidHeadService extends Service implements TextToSpeech.OnInitListener, SensorEventListener {
    private static final int FOREGROUND_NOTIFICATION_ID = 100;
    private static final String NOTIFICATION_CHANNEL_ID = "droid_video_recorder_service";
    private static final int CHAT_HEAD_SIZE_DP = 122;
    private static final float TWIST_THRESHOLD = 5.5f;
    private static final long TWIST_SEQUENCE_TIMEOUT_MS = 900;
    private static final long TWIST_COOLDOWN_MS = 1800;
    private static boolean serviceActive;

    private WindowManager windowManager;
    private ImageView chatHead;
    private TextView txtHead;
    private TextView txtCameraBadge;
    private SurfaceView mSurfaceView;
    private TextureView readyPreviewView;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private int orientationEvent;
    private Context context;
    private AsyncTask asyncTask;
    private View.OnTouchListener onTouchListener;
    private String chamadaPorComandoTexto;
    private boolean pendingPreview;
    private DroidConstants.EnumTypeViewCam pendingPreviewCam = DroidConstants.EnumTypeViewCam.FacingBack;
    private boolean pendingReadyPreview;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SensorManager sensorManager;
    private Sensor gyroscopeSensor;
    private Notification.Builder notificationBuilder;
    private NotificationManager notificationManager;
    private int lastTwistDirection;
    private int twistCount;
    private long lastTwistMoveTime;
    private long lastTwistCommandTime;

    private boolean necessarioComandoDepoisDoInit = false;
    private Intent mIntentService;
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

    private static int getOverlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
        if (ComandoPorTexto("MIR")) {
            GravarModoOculto();
        } else if (ComandoPorTexto("R")) {
            Gravar();
        } else if (ComandoPorTexto("CFG")) {
            AbrirConfig();
        } else Abrir();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("DVR", "DroidHeadService onStartCommand");
        mIntentService = intent != null ? intent : new Intent();
        Log.d("DVR", "Comando recebido pelo servico: " + mIntentService.getStringExtra(DroidConstants.CHAMADAPORCOMANDOTEXTO));

        if (ComandoPorTexto("MI")) {
            ModoOculto();
        } else if (ComandoPorTexto("MIR")) {
            ModoOcultoSemFala();
        } else if (ComandoPorTexto("MV")) {
            ModoVisivel();
        } else if (ComandoPorTexto("S")) {
            Parar();
        } else if (ComandoPorTexto("V")) {
            Visualizar();
        } else if (ComandoPorTexto("R")) {
            Gravar();
        } else if (ComandoPorTexto("C")) {
            Fechar();
        } else if (ComandoPorTexto("Q")) {
            Sair();
        }

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getBaseContext();
        DroidVideoRecorder.TypeViewCam = DroidPrefsUtils.obtemUltimaCamera(context);
        serviceActive = true;
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
        super.onDestroy();
        DroidVideoRecorder.OnStopRecording(false);
        if (chatHead != null) windowManager.removeView(chatHead);
        if (txtHead != null) windowManager.removeView(txtHead);
        if (txtCameraBadge != null) windowManager.removeView(txtCameraBadge);
        if (mSurfaceView != null) windowManager.removeView(mSurfaceView);
        if (readyPreviewView != null) windowManager.removeView(readyPreviewView);
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
        txtHead.setTextSize(10);
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
        txtHead.setTextSize(12);
        txtHead.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        txtHead.setPadding(0, 0, 0, dp(8));
        if (chatHead.getVisibility() == View.VISIBLE) {
            txtHead.setVisibility(View.VISIBLE);
        }
    }

    private void InicializarVariavel() {
        context = getBaseContext();

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
                StartPendingReadyPreview();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
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

        chatHead = new ImageView(context);
        chatHead.setImageResource(R.mipmap.viewrec);
        params.width = dp(CHAT_HEAD_SIZE_DP);
        params.height = dp(CHAT_HEAD_SIZE_DP);
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
        txtCameraBadge.setPadding(0, 0, 0, dp(5));
        txtCameraBadge.setTextSize(9);
        txtCameraBadge.setShadowLayer(3, 0, 1, Color.BLACK);
        txtCameraBadge.setVisibility(View.INVISIBLE);

        params.gravity = Gravity.CENTER;
        surfaceParams.gravity = Gravity.CENTER;
        readyPreviewParams.gravity = Gravity.CENTER;
        windowManager.addView(mSurfaceView, surfaceParams);
        windowManager.addView(readyPreviewView, readyPreviewParams);
        windowManager.addView(chatHead, params);
        windowManager.addView(txtHead, params);
        windowManager.addView(txtCameraBadge, params);
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
        //context.stopService(mIntentService);
        stopSelf();
        tts.shutdown();
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

    private boolean ComandoPorTexto(String cmd) {
        boolean ret = false;
        if (DroidPrefsUtils.aceitaComandoPorTexto(context)) {
            chamadaPorComandoTexto = mIntentService.getStringExtra(DroidConstants.CHAMADAPORCOMANDOTEXTO);
            if (chamadaPorComandoTexto != null) {
                ret = chamadaPorComandoTexto.equalsIgnoreCase(DroidConstants.COMANDOINICIADOPOR + cmd);
            }
        }
        return ret;
    }

    private void Abrir() {
        Fala(getString(R.string.abrindoDVR));
    }

    private void AbrirConfig() {
        Fala(getString(R.string.abrindoDVRConfig));
        ShowActivity();
    }

    private void Gravar() {
        Gravacao(false);
    }

    private void Gravacao(boolean gravarModoOculto) {
        if (necessarioComandoDepoisDoInit) {
            if (Permite(DroidVideoRecorder.StateRecVideo.RECORD)) {
                if (gravarModoOculto) {
                    Fala(getString(R.string.gravandoModoOculto));
                } else {
                    Fala(getString(R.string.gravando));
                }
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

    private void NaoEntendi() {
        Fala(getString(R.string.naoEntendi));
    }

    private void GravarModoOculto() {
        Gravacao(true);
    }

    private void ModoOculto() {
        Fala(getString(R.string.modoOculto));
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            HideReadyPreview();
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD
                && readyPreviewView != null) {
            readyPreviewView.setAlpha(0f);
        }
        chatHead.setVisibility(View.INVISIBLE);
        txtHead.setVisibility(View.INVISIBLE);
        HideRecordingBadge();
        UpdateNotification(getString(R.string.notification_hidden_mode));
    }
    private void ModoOcultoSemFala() {
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            HideReadyPreview();
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD
                && readyPreviewView != null) {
            readyPreviewView.setAlpha(0f);
        }
        chatHead.setVisibility(View.INVISIBLE);
        txtHead.setVisibility(View.INVISIBLE);
        HideRecordingBadge();
        UpdateNotification(getString(R.string.notification_hidden_mode));
    }

    private void ModoVisivel() {
        Fala(getString(R.string.modoVisivel));
        chatHead.setVisibility(View.VISIBLE);
        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
            ShowReadyPreview();
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            if (readyPreviewView != null) {
                readyPreviewView.setAlpha(1f);
                readyPreviewView.setVisibility(View.VISIBLE);
            }
            txtHead.setVisibility(View.VISIBLE);
        }

        if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD) {
            UpdateNotification(GetRecordingNotificationText());
        } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
            UpdateNotification(GetViewingNotificationText());
        } else {
            UpdateNotification(GetReadyNotificationText());
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
        HideCameraIndicator();
        ShowRecordingPreview();
        SetPreviewFullScreen(false);
        DroidVideoRecorder.OnInitRec(getResources().getConfiguration(), orientationEvent, DroidVideoRecorder.TypeViewCam);
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
        DroidVideoRecorder.OnStopRecording(record);
        if (record && asyncTask != null) {
            asyncTask.cancel(true);
        }
        ShowStop();
        Vibrar(50);
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
    }

    private void ShowRecordingPreview() {
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
        pendingReadyPreview = false;
        if (readyPreviewView != null) {
            readyPreviewView.setVisibility(View.INVISIBLE);
        }
        DroidVideoRecorder.OnStopRecording(false);
        SetPreviewFullScreen(false);
    }

    private void SetPreviewBubble() {
        readyPreviewParams.width = dp(CHAT_HEAD_SIZE_DP);
        readyPreviewParams.height = dp(CHAT_HEAD_SIZE_DP);
        readyPreviewParams.gravity = Gravity.CENTER;
        readyPreviewParams.x = params.x;
        readyPreviewParams.y = params.y;
        try {
            readyPreviewView.setAlpha(1f);
            readyPreviewView.setVisibility(View.VISIBLE);
            windowManager.updateViewLayout(readyPreviewView, readyPreviewParams);
        } catch (Exception ex) {
            Log.d("DVR", ex.getMessage());
        }
    }

    private GradientDrawable CreatePreviewBorder(int color) {
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.OVAL);
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(3), color);
        return border;
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

    public class TouchListener implements View.OnTouchListener {

        private GestureDetector gestureDetector = new GestureDetector(DroidHeadService.this, new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onDoubleTap(MotionEvent e) {

                if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
                    Fechar();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.CLOSE) {
                    AbrirConfig();
                    ShowStop();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
                    GetDefaultStop();
                }

                return super.onDoubleTap(e);
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP) {
                    Visualizar();
                } else if (DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.VIEW) {
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

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            gestureDetector.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    if (myOrientationEventListener.canDetectOrientation()) {
                        myOrientationEventListener.enable();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    Integer totalMoveX = (int) (event.getRawX() - initialTouchX);
                    params.x = initialX + totalMoveX;
                    Integer totalMoveY = (int) (event.getRawY() - initialTouchY);
                    params.y = initialY + totalMoveY;
                    windowManager.updateViewLayout(chatHead, params);
                    windowManager.updateViewLayout(txtHead, params);
                    windowManager.updateViewLayout(txtCameraBadge, params);
                    if ((DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.STOP
                            || DroidVideoRecorder.StateRecVideo == DroidConstants.EnumStateRecVideo.RECORD)
                            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        readyPreviewParams.x = params.x;
                        readyPreviewParams.y = params.y;
                        windowManager.updateViewLayout(readyPreviewView, readyPreviewParams);
                    }
                    return true;
            }

            return true;
        }

    }

}






