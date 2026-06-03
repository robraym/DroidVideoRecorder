package com.droid.videoRecorder;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaScannerConnection;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Log;

@UnstableApi
public class DroidVideoRecorder {
    private static Camera mServiceCamera;
    private static MediaRecorder mMediaRecorder;
    private static Surface mRecordingPreviewSurface;
    private static Context appContext;
    private static Uri currentVideoUri;
    private static ParcelFileDescriptor currentVideoFile;
    private static String currentLegacyVideoPath;
    private static String currentVideoDisplayName;
    private static boolean currentShouldMirrorFrontVideo;
    private static int currentPreviewWidth;
    private static int currentPreviewHeight;
    private static int currentCameraId = -1;
    private static boolean mediaRecorderStarted;
    private static final Handler MIRROR_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService MIRROR_COPY_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ArrayDeque<PendingMirroredSelfie> PENDING_MIRRORED_SELFIES = new ArrayDeque<>();
    private static Transformer activeMirrorTransformer;
    private static PendingMirroredSelfie activeMirroredSelfie;

    public static DroidConstants.EnumStateRecVideo StateRecVideo;
    public static DroidConstants.EnumTypeViewCam TypeViewCam;
    public static int LocalGravacaoVideo = 0;
    private static final int[] BEST_VIDEO_QUALITIES = new int[]{
            CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_720P,
            CamcorderProfile.QUALITY_480P,
            CamcorderProfile.QUALITY_HIGH,
            CamcorderProfile.QUALITY_LOW
    };

    public static void SetContext(Context context) {
        appContext = context.getApplicationContext();
    }

    private static void TimeSleep(Integer seg) {
        try {
            Thread.sleep(seg);
        } catch (Exception ex) {
        }
    }

    public static boolean isExternalStorageMediaMounted() {
        return (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()));
    }

    public static String GetPathStorage() {
        String strSDCardPath = "";
        String strDirectory = "";

        try {

            if (LocalGravacaoVideo == 1) { // Cartao SD
                if (isExternalStorageMediaMounted()) {
                    strSDCardPath = System.getenv("SECONDARY_STORAGE");
                    if ((null == strSDCardPath) || (strSDCardPath.length() == 0)) {
                        strSDCardPath = System.getenv("EXTERNAL_SDCARD_STORAGE");
                    }
                    strDirectory = CreateGetDirectory(strSDCardPath + DroidConstants.PASTADOSARQUIVOSGRAVADOS);
                }
            }
        } catch (Exception e) {
        } finally {
            if (strSDCardPath == "" || strDirectory == "") {
                if (strDirectory == "") {
                    File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Recorder");
                    strDirectory = CreateGetDirectory(directory.getAbsolutePath());
                }
            }
        }
        return strDirectory;
    }


    public static String CreateGetDirectory(String pathStorage)
    {
        String pathDirectory = "";

        try {

            File myNewFolder = new File(pathStorage);

            if (!myNewFolder.exists()) {
                myNewFolder.mkdirs();
                TimeSleep(1000);
            }
            if (myNewFolder.exists())
            {
                pathDirectory = pathStorage;
            }
        }
        catch (Exception e)
        {

        }
        return pathDirectory;
    }



    private static String NameFileRecDateNow()
    {
        SimpleDateFormat simpleFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String dateformat = simpleFormat.format( new Date( System.currentTimeMillis() ));
        return GetPathStorage() + "/DVR_" + dateformat +  ".mp4";
    }

    private static String NameFileDateNow() {
        SimpleDateFormat simpleFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
        return "DVR_" + simpleFormat.format(new Date(System.currentTimeMillis())) + ".mp4";
    }

    private static void SetOutputFile(MediaRecorder mediaRecorder) throws IOException {
        CloseCurrentVideoFile();
        currentVideoUri = null;
        currentLegacyVideoPath = null;
        currentVideoDisplayName = NameFileDateNow();
        currentShouldMirrorFrontVideo = appContext != null
                && TypeViewCam == DroidConstants.EnumTypeViewCam.FacingFront
                && DroidPrefsUtils.salvaSelfiesComoVisualizadas(appContext);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appContext != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, currentVideoDisplayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Video Recorder");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            currentVideoUri = appContext.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (currentVideoUri == null) {
                throw new IOException("Nao foi possivel criar o arquivo de video");
            }

            currentVideoFile = appContext.getContentResolver().openFileDescriptor(currentVideoUri, "w");
            if (currentVideoFile == null) {
                throw new IOException("Nao foi possivel abrir o arquivo de video");
            }

            mediaRecorder.setOutputFile(currentVideoFile.getFileDescriptor());
            return;
        }

        currentLegacyVideoPath = NameFileRecDateNow();
        mediaRecorder.setOutputFile(currentLegacyVideoPath);
    }

    private static void FinishCurrentVideoFile() {
        CloseCurrentVideoFile();

        Uri videoUri = currentVideoUri;
        String legacyVideoPath = currentLegacyVideoPath;
        String displayName = currentVideoDisplayName;
        boolean shouldMirrorFrontVideo = currentShouldMirrorFrontVideo;

        PublishMediaStoreVideo(videoUri);
        if (shouldMirrorFrontVideo) {
            QueueMirroredSelfie(videoUri, legacyVideoPath, displayName);
        } else if (legacyVideoPath != null) {
            ScanLegacyVideo(legacyVideoPath);
        }

        ClearCurrentVideoState();
    }

    private static void DeleteCurrentVideoFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appContext != null && currentVideoUri != null) {
            appContext.getContentResolver().delete(currentVideoUri, null, null);
        }
        CloseCurrentVideoFile();
        if (currentLegacyVideoPath != null) {
            new File(currentLegacyVideoPath).delete();
        }
        ClearCurrentVideoState();
    }

    private static void PublishMediaStoreVideo(Uri videoUri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appContext != null && videoUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            appContext.getContentResolver().update(videoUri, values, null, null);
        }
    }

    private static void ClearCurrentVideoState() {
        currentVideoUri = null;
        currentLegacyVideoPath = null;
        currentVideoDisplayName = null;
        currentShouldMirrorFrontVideo = false;
    }

    private static void CloseCurrentVideoFile() {
        if (currentVideoFile != null) {
            try {
                currentVideoFile.close();
            } catch (IOException ex) {
                LogException("StartRecording", ex);
            }
            currentVideoFile = null;
        }
    }

    private static void QueueMirroredSelfie(Uri videoUri, String legacyVideoPath, String displayName) {
        if (appContext == null || (videoUri == null && legacyVideoPath == null)) {
            return;
        }

        PendingMirroredSelfie selfie = new PendingMirroredSelfie(videoUri, legacyVideoPath, displayName);
        MIRROR_HANDLER.post(() -> {
            PENDING_MIRRORED_SELFIES.offer(selfie);
            StartNextMirroredSelfie();
        });
    }

    private static void StartNextMirroredSelfie() {
        if (activeMirrorTransformer != null || PENDING_MIRRORED_SELFIES.isEmpty() || appContext == null) {
            return;
        }

        activeMirroredSelfie = PENDING_MIRRORED_SELFIES.poll();
        try {
            activeMirroredSelfie.transformedFile = File.createTempFile(
                    "DVR_mirrored_",
                    ".mp4",
                    appContext.getCacheDir());

            ScaleAndRotateTransformation mirrorEffect = new ScaleAndRotateTransformation.Builder()
                    .setScale(-1f, 1f)
                    .build();
            Effects effects = new Effects(
                    Collections.emptyList(),
                    Collections.singletonList(mirrorEffect));
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(
                    MediaItem.fromUri(activeMirroredSelfie.GetInputUri()))
                    .setEffects(effects)
                    .build();

            activeMirrorTransformer = new Transformer.Builder(appContext)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            ReplaceActiveMirroredSelfie();
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException exception) {
                            LogException("MirrorSelfie", exception);
                            FinishActiveMirroredSelfie();
                        }
                    })
                    .build();
            activeMirrorTransformer.start(editedMediaItem, activeMirroredSelfie.transformedFile.getAbsolutePath());
        } catch (Exception ex) {
            LogException("MirrorSelfie", ex);
            FinishActiveMirroredSelfie();
        }
    }

    private static void ReplaceActiveMirroredSelfie() {
        PendingMirroredSelfie selfie = activeMirroredSelfie;
        if (selfie == null || selfie.transformedFile == null) {
            FinishActiveMirroredSelfie();
            return;
        }

        MIRROR_COPY_EXECUTOR.execute(() -> {
            try {
                if (selfie.videoUri != null) {
                    ReplaceMediaStoreVideo(selfie);
                } else {
                    ReplaceLegacyVideo(selfie);
                }
            } catch (Exception ex) {
                LogException("MirrorSelfie", ex);
            }
            MIRROR_HANDLER.post(DroidVideoRecorder::FinishActiveMirroredSelfie);
        });
    }

    private static void ReplaceMediaStoreVideo(PendingMirroredSelfie selfie) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, selfie.displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Video Recorder");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri mirroredVideoUri = appContext.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (mirroredVideoUri == null) {
            throw new IOException("Nao foi possivel criar o video selfie espelhado");
        }

        try {
            try (InputStream input = new FileInputStream(selfie.transformedFile);
                 OutputStream output = appContext.getContentResolver().openOutputStream(mirroredVideoUri, "w")) {
                if (output == null) {
                    throw new IOException("Nao foi possivel abrir o video selfie espelhado");
                }
                Copy(input, output);
            }
            PublishMediaStoreVideo(mirroredVideoUri);
            appContext.getContentResolver().delete(selfie.videoUri, null, null);
        } catch (Exception ex) {
            appContext.getContentResolver().delete(mirroredVideoUri, null, null);
            throw ex;
        }
    }

    private static void ReplaceLegacyVideo(PendingMirroredSelfie selfie) throws IOException {
        File original = new File(selfie.legacyVideoPath);
        File replacement = new File(selfie.legacyVideoPath + ".mirror.tmp");
        File backup = new File(selfie.legacyVideoPath + ".original.tmp");

        try (InputStream input = new FileInputStream(selfie.transformedFile);
             OutputStream output = new FileOutputStream(replacement)) {
            Copy(input, output);
        }

        if (!original.renameTo(backup)) {
            replacement.delete();
            throw new IOException("Nao foi possivel preparar o video selfie original");
        }
        if (!replacement.renameTo(original)) {
            backup.renameTo(original);
            replacement.delete();
            throw new IOException("Nao foi possivel salvar o video selfie espelhado");
        }

        backup.delete();
        ScanLegacyVideo(original.getAbsolutePath());
    }

    private static void Copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static void ScanLegacyVideo(String path) {
        if (appContext != null) {
            MediaScannerConnection.scanFile(appContext, new String[]{path}, new String[]{"video/mp4"}, null);
        }
    }

    private static void FinishActiveMirroredSelfie() {
        if (activeMirroredSelfie != null && activeMirroredSelfie.transformedFile != null) {
            activeMirroredSelfie.transformedFile.delete();
        }
        activeMirrorTransformer = null;
        activeMirroredSelfie = null;
        StartNextMirroredSelfie();
    }

    private static class PendingMirroredSelfie {
        final Uri videoUri;
        final String legacyVideoPath;
        final String displayName;
        File transformedFile;

        PendingMirroredSelfie(Uri videoUri, String legacyVideoPath, String displayName) {
            this.videoUri = videoUri;
            this.legacyVideoPath = legacyVideoPath;
            this.displayName = displayName;
        }

        Uri GetInputUri() {
            return videoUri != null ? videoUri : Uri.fromFile(new File(legacyVideoPath));
        }
    }

    private static void LogException(String tag, Exception ex) {
        String message = ex.getMessage();
        Log.d(tag, message != null ? message : ex.toString());
    }

    private static int GetDisplayOrientationRec(int orientation)
    {
        int displayOrient = 0;

        if (orientation > 315 || orientation <= 45)
        {
            if (TypeViewCam == DroidConstants.EnumTypeViewCam.FacingFront)
            {
                displayOrient = 270;
            }
            else {
                displayOrient = 90;
            }
        }
        else if (orientation > 45 && orientation <= 135)
        {
            displayOrient =  180;
        }
        else if (orientation > 135 && orientation <= 225)
        {
            displayOrient =  270;
        }
        else if (orientation > 225 && orientation <= 315)
        {
            displayOrient = 0;
        }
        return displayOrient;

    }


    private static int GetDisplayOrientationView(Configuration orient, int orientation )
    {
        int displayOrient = 0;

        if (orient.orientation != Configuration.ORIENTATION_LANDSCAPE) {

            displayOrient = 90;

        } else {

            if (orientation > 45 && orientation <= 135)
            {
                displayOrient =  180;
            }
            else if (orientation > 135 && orientation <= 225)
            {
                displayOrient =  270;
            }

        }

        return displayOrient;
    }

    private static int FindCameraId(int cameraFacing) {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == cameraFacing) {
                return i;
            }
        }
        return 0;
    }

    private static CamcorderProfile GetBestCamcorderProfile() {
        int cameraId = currentCameraId >= 0 ? currentCameraId : 0;
        for (int quality : BEST_VIDEO_QUALITIES) {
            if (CamcorderProfile.hasProfile(cameraId, quality)) {
                Log.d("DVR", "Qualidade de video automatica: " + quality);
                return CamcorderProfile.get(cameraId, quality);
            }
        }
        return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW);
    }

    private static void ConfigureFocus(Camera.Parameters parameters) {
        List<String> supportedFocusModes = parameters.getSupportedFocusModes();
        if (supportedFocusModes == null) {
            return;
        }

        if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }
    }

    private static Camera.Size GetBestPreviewSize(List<Camera.Size> previewSizes) {
        if (previewSizes == null || previewSizes.isEmpty()) {
            return null;
        }

        Camera.Size bestSize = previewSizes.get(0);
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size size : previewSizes) {
            double aspectRatio = Math.max(size.width, size.height) / (double) Math.min(size.width, size.height);
            double aspectDistance = Math.abs(aspectRatio - (16d / 9d));
            double sizeDistance = Math.abs(Math.max(size.width, size.height) - 1280)
                    + Math.abs(Math.min(size.width, size.height) - 720);
            double score = aspectDistance * 10000d + sizeDistance;
            if (score < bestScore) {
                bestScore = score;
                bestSize = size;
            }
        }
        return bestSize;
    }

    public static float GetPreviewAspectRatio() {
        if (currentPreviewWidth <= 0 || currentPreviewHeight <= 0) {
            return 16f / 9f;
        }
        return Math.max(currentPreviewWidth, currentPreviewHeight)
                / (float) Math.min(currentPreviewWidth, currentPreviewHeight);
    }

    public static void OnInitRec (Configuration orient, int orientation, DroidConstants.EnumTypeViewCam typeViewCam)
    {
        try {
            if (typeViewCam == DroidConstants.EnumTypeViewCam.FacingFront)
            {
                currentCameraId = FindCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT);
                TypeViewCam = DroidConstants.EnumTypeViewCam.FacingFront;
            }
            else {
                currentCameraId = FindCameraId(Camera.CameraInfo.CAMERA_FACING_BACK);
                TypeViewCam = DroidConstants.EnumTypeViewCam.FacingBack;
            }

            if(mServiceCamera == null) {
                mServiceCamera = Camera.open(currentCameraId);

                Camera.Parameters params = mServiceCamera.getParameters();
                params.set("cam_mode", 1);
                mServiceCamera.setParameters(params);
                Camera.Parameters p = mServiceCamera.getParameters();

                final List<Camera.Size> listSize = p.getSupportedPreviewSizes();
                Camera.Size mPreviewSize = GetBestPreviewSize(listSize);

                mServiceCamera.setDisplayOrientation(GetDisplayOrientationView(orient, orientation));

                ConfigureFocus(p);
                if (mPreviewSize != null) {
                    p.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
                    currentPreviewWidth = mPreviewSize.width;
                    currentPreviewHeight = mPreviewSize.height;
                    Log.d("DVR", "Tamanho da previa: " + mPreviewSize.width + "x" + mPreviewSize.height);
                }
                mServiceCamera.setParameters(p);
            }



        }
        catch (Exception ex)
        {
            LogException("ViewRec", ex);
        }

    }

    public static void OnViewRec(SurfaceHolder surfaceHolder)
    {
        try {

            try {
                mServiceCamera.setPreviewDisplay(surfaceHolder);
                mServiceCamera.startPreview();
                mServiceCamera.cancelAutoFocus();
            }
            catch (IOException e) {
                // TODO Auto-generated catch block
            }
        }
        catch (Exception ex)
        {
            LogException("ViewRec", ex);
        }

    }

    public static void OnViewRec(SurfaceTexture surfaceTexture)
    {
        try {
            mServiceCamera.setPreviewTexture(surfaceTexture);
            mServiceCamera.startPreview();
            mServiceCamera.cancelAutoFocus();
        }
        catch (Exception ex)
        {
            LogException("ViewRec", ex);
        }
    }


    public static boolean OnStartRecording(SurfaceHolder surfaceHolder, int orientation)
    {
        return OnStartRecording(surfaceHolder.getSurface(), orientation);
    }

    public static boolean OnStartRecording(SurfaceTexture surfaceTexture, int orientation)
    {
        mRecordingPreviewSurface = new Surface(surfaceTexture);
        return OnStartRecording(mRecordingPreviewSurface, orientation);
    }

    private static boolean OnStartRecording(Surface previewSurface, int orientation)
    {
        try {
            mediaRecorderStarted = false;

            mServiceCamera.unlock();

            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setCamera(mServiceCamera);
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            SetOutputFile(mMediaRecorder);

            mMediaRecorder.setProfile(GetBestCamcorderProfile());

            mMediaRecorder.setPreviewDisplay(previewSurface);
            mMediaRecorder.setOrientationHint(GetDisplayOrientationRec(orientation));

            mMediaRecorder.prepare();
            mMediaRecorder.start();
            mediaRecorderStarted = true;
            return true;
        }
        catch (Exception ex)
        {
            LogException("StartRecording", ex);
            DeleteCurrentVideoFile();
            ResetRecord(false);
            return false;
        }
    }

    private static void ResetRecord(boolean record)
    {
        if (record && mMediaRecorder != null && mediaRecorderStarted) {
            try {
                mMediaRecorder.stop();
                FinishCurrentVideoFile();
            } catch (Exception ex) {
                LogException("StopRecording", ex);
                DeleteCurrentVideoFile();
            }
        } else if (record) {
            DeleteCurrentVideoFile();
        }

        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.reset();
                mMediaRecorder.release();
            } catch (Exception ex) {
                LogException("StopRecording", ex);
            }
            mMediaRecorder = null;
            mediaRecorderStarted = false;
        }

        if (mRecordingPreviewSurface != null) {
            mRecordingPreviewSurface.release();
            mRecordingPreviewSurface = null;
        }

        if (mServiceCamera != null) {
            try {
                mServiceCamera.stopPreview();
            } catch (Exception ex) {
                LogException("StopPreview", ex);
            }
            mServiceCamera.release();
            mServiceCamera = null;
        }

    }

    public static void OnStopRecording(boolean record) {

        try {
            if (mServiceCamera != null) {
                mServiceCamera.reconnect();
            }
        } catch (IOException ex) {
            LogException("StopRecording", ex);
        }
        ResetRecord(record);
    }
}
