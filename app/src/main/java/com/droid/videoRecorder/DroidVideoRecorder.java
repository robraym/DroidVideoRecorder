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
import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
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
import java.util.Set;
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
    private static boolean currentVideoIsFrontCamera;
    private static int currentPreviewWidth;
    private static int currentPreviewHeight;
    private static int currentCameraId = -1;
    private static boolean mediaRecorderStarted;
    private static final Handler MIRROR_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService MIRROR_COPY_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ArrayDeque<PendingMirroredSelfie> PENDING_MIRRORED_SELFIES = new ArrayDeque<>();
    private static Transformer activeMirrorTransformer;
    private static PendingMirroredSelfie activeMirroredSelfie;
    private static PendingVideoEnhancement activeVideoEnhancement;
    private static boolean activeAudioEnhancement;
    private static boolean pendingVideoProcessing;
    private static boolean copyingMirroredSelfie;
    private static final ProgressHolder MIRROR_PROGRESS_HOLDER = new ProgressHolder();

    public interface RecordedVideoListener {
        void OnRecordedVideoReady(RecordedVideo video);
    }

    public interface VideoEnhancementListener {
        void OnVideoEnhanced(RecordedVideo video);
        void OnVideoEnhancementFailed();
        default void OnVideoEnhancementProgress(int percent, int estimatedSecondsRemaining) {
        }
    }

    public static class RecordedVideo {
        public final Uri uri;
        public final String legacyPath;
        public final String displayName;
        public final boolean frontCamera;
        public final boolean selfieAsPreviewed;

        RecordedVideo(Uri uri, String legacyPath, String displayName) {
            this(uri, legacyPath, displayName, false, false);
        }

        RecordedVideo(Uri uri, String legacyPath, String displayName,
                      boolean frontCamera, boolean selfieAsPreviewed) {
            this.uri = uri;
            this.legacyPath = legacyPath;
            this.displayName = displayName;
            this.frontCamera = frontCamera;
            this.selfieAsPreviewed = selfieAsPreviewed;
        }

        public boolean HasVideo() {
            return uri != null || legacyPath != null;
        }
    }

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

    private static String GetMediaStoreRelativePath() {
        if (LocalGravacaoVideo == DroidPrefsUtils.LOCAL_GRAVACAO_CAMERA) {
            return Environment.DIRECTORY_DCIM + "/Camera";
        }
        return Environment.DIRECTORY_MOVIES + "/Recorder";
    }

    private static Uri GetMediaStoreCollectionUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && appContext != null
                && LocalGravacaoVideo == DroidPrefsUtils.LOCAL_GRAVACAO_CARTAO_SD
                && DroidPrefsUtils.temCartaoSd(appContext)) {
            Set<String> volumes = MediaStore.getExternalVolumeNames(appContext);
            for (String volume : volumes) {
                if (!MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume)
                        && !MediaStore.VOLUME_EXTERNAL.equals(volume)) {
                    return MediaStore.Video.Media.getContentUri(volume);
                }
            }
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    public static String GetPathStorage() {
        try {
            if (LocalGravacaoVideo == DroidPrefsUtils.LOCAL_GRAVACAO_CARTAO_SD) {
                File sdDirectory = appContext != null ? DroidPrefsUtils.obtemDiretorioCartaoSd(appContext) : null;
                if (sdDirectory != null) {
                    String directory = CreateGetDirectory(new File(sdDirectory, "Recorder").getAbsolutePath());
                    if (directory.length() > 0) {
                        return directory;
                    }
                }

                if (isExternalStorageMediaMounted()) {
                    String sdCardPath = System.getenv("SECONDARY_STORAGE");
                    if ((sdCardPath == null) || (sdCardPath.length() == 0)) {
                        sdCardPath = System.getenv("EXTERNAL_SDCARD_STORAGE");
                    }
                    if (sdCardPath != null && sdCardPath.length() > 0) {
                        String directory = CreateGetDirectory(sdCardPath + DroidConstants.PASTADOSARQUIVOSGRAVADOS);
                        if (directory.length() > 0) {
                            return directory;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        File publicDirectory;
        if (LocalGravacaoVideo == DroidPrefsUtils.LOCAL_GRAVACAO_CAMERA) {
            publicDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
        } else {
            publicDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Recorder");
        }
        return CreateGetDirectory(publicDirectory.getAbsolutePath());
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
        currentVideoIsFrontCamera = TypeViewCam == DroidConstants.EnumTypeViewCam.FacingFront;
        currentShouldMirrorFrontVideo = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appContext != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, currentVideoDisplayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, GetMediaStoreRelativePath());
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            currentVideoUri = appContext.getContentResolver().insert(GetMediaStoreCollectionUri(), values);
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

    private static RecordedVideo FinishCurrentVideoFile(RecordedVideoListener listener) {
        CloseCurrentVideoFile();

        Uri videoUri = currentVideoUri;
        String legacyVideoPath = currentLegacyVideoPath;
        String displayName = currentVideoDisplayName;
        boolean shouldMirrorFrontVideo = currentShouldMirrorFrontVideo;
        boolean isFrontCamera = currentVideoIsFrontCamera;

        PublishMediaStoreVideo(videoUri);
        if (shouldMirrorFrontVideo) {
            QueueMirroredSelfie(videoUri, legacyVideoPath, displayName, listener);
        } else if (legacyVideoPath != null) {
            ScanLegacyVideo(legacyVideoPath);
        }

        ClearCurrentVideoState();
        if (shouldMirrorFrontVideo && listener != null) {
            return null;
        }
        return new RecordedVideo(videoUri, legacyVideoPath, displayName, isFrontCamera, false);
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
        currentVideoIsFrontCamera = false;
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

    private static void QueueMirroredSelfie(Uri videoUri, String legacyVideoPath, String displayName,
                                            RecordedVideoListener listener) {
        if (appContext == null || (videoUri == null && legacyVideoPath == null)) {
            NotifyRecordedVideo(listener, new RecordedVideo(videoUri, legacyVideoPath, displayName, true, false));
            return;
        }

        pendingVideoProcessing = true;
        copyingMirroredSelfie = false;
        PendingMirroredSelfie selfie = new PendingMirroredSelfie(videoUri, legacyVideoPath, displayName, listener);
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
                            NotifyRecordedVideo(activeMirroredSelfie);
                            FinishActiveMirroredSelfie();
                        }
                    })
                    .build();
            activeMirrorTransformer.start(editedMediaItem, activeMirroredSelfie.transformedFile.getAbsolutePath());
        } catch (Exception ex) {
            LogException("MirrorSelfie", ex);
            NotifyRecordedVideo(activeMirroredSelfie);
            FinishActiveMirroredSelfie();
        }
    }

    private static void ReplaceActiveMirroredSelfie() {
        PendingMirroredSelfie selfie = activeMirroredSelfie;
        if (selfie == null || selfie.transformedFile == null) {
            FinishActiveMirroredSelfie();
            return;
        }

        copyingMirroredSelfie = true;
        MIRROR_COPY_EXECUTOR.execute(() -> {
            RecordedVideo recordedVideo = null;
            try {
                if (selfie.videoUri != null) {
                    Uri mirroredVideoUri = ReplaceMediaStoreVideo(selfie);
                    recordedVideo = new RecordedVideo(mirroredVideoUri, null, selfie.displayName, true, true);
                } else {
                    ReplaceLegacyVideo(selfie);
                    recordedVideo = new RecordedVideo(null, selfie.legacyVideoPath, selfie.displayName, true, true);
                }
            } catch (Exception ex) {
                LogException("MirrorSelfie", ex);
                recordedVideo = new RecordedVideo(selfie.videoUri, selfie.legacyVideoPath, selfie.displayName, true, false);
            }
            RecordedVideo finalRecordedVideo = recordedVideo;
            MIRROR_HANDLER.post(() -> {
                NotifyRecordedVideo(selfie.listener, finalRecordedVideo);
                FinishActiveMirroredSelfie();
            });
        });
    }

    private static Uri ReplaceMediaStoreVideo(PendingMirroredSelfie selfie) throws IOException {
        return ReplaceMediaStoreVideo(selfie.videoUri, selfie.transformedFile, selfie.displayName, true);
    }

    private static Uri ReplaceMediaStoreVideo(Uri sourceUri, File transformedFile, String displayName,
                                              boolean deleteSource) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName != null ? displayName : NameFileDateNow());
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, GetMediaStoreRelativePath());
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri replacementVideoUri = appContext.getContentResolver().insert(GetMediaStoreCollectionUri(), values);
        if (replacementVideoUri == null) {
            throw new IOException("Nao foi possivel criar o video processado");
        }

        try {
            try (InputStream input = new FileInputStream(transformedFile);
                 OutputStream output = appContext.getContentResolver().openOutputStream(replacementVideoUri, "w")) {
                if (output == null) {
                    throw new IOException("Nao foi possivel abrir o video processado");
                }
                Copy(input, output);
            }
            PublishMediaStoreVideo(replacementVideoUri);
            if (deleteSource && sourceUri != null) {
                appContext.getContentResolver().delete(sourceUri, null, null);
            }
            return replacementVideoUri;
        } catch (Exception ex) {
            appContext.getContentResolver().delete(replacementVideoUri, null, null);
            throw ex;
        }
    }

    private static void ReplaceLegacyVideo(PendingMirroredSelfie selfie) throws IOException {
        ReplaceLegacyVideo(selfie.legacyVideoPath, selfie.transformedFile, ".mirror");
    }

    private static void ReplaceLegacyVideo(String legacyVideoPath, File transformedFile, String suffix) throws IOException {
        File original = new File(legacyVideoPath);
        File replacement = new File(legacyVideoPath + suffix + ".tmp");
        File backup = new File(legacyVideoPath + ".original.tmp");

        try (InputStream input = new FileInputStream(transformedFile);
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

    public static boolean EnhanceVideo(Context context, RecordedVideo video, VideoEnhancementListener listener) {
        if (context != null) {
            SetContext(context);
        }
        return false;
    }

    public static boolean SaveSelfieAsPreviewed(Context context, RecordedVideo video,
                                                VideoEnhancementListener listener) {
        if (context != null) {
            SetContext(context);
        }
        if (appContext == null || video == null || !video.HasVideo() || !video.frontCamera) {
            return false;
        }
        if (activeMirrorTransformer != null || activeVideoEnhancement != null || activeAudioEnhancement) {
            return false;
        }

        try {
            activeVideoEnhancement = new PendingVideoEnhancement(video, listener);
            activeVideoEnhancement.transformedFile = File.createTempFile(
                    "DVR_selfie_preview_",
                    ".mp4",
                    appContext.getCacheDir());

            ScaleAndRotateTransformation mirrorEffect = new ScaleAndRotateTransformation.Builder()
                    .setScale(-1f, 1f)
                    .build();
            Effects effects = new Effects(
                    Collections.emptyList(),
                    Collections.singletonList(mirrorEffect));
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(
                    MediaItem.fromUri(activeVideoEnhancement.GetInputUri()))
                    .setEffects(effects)
                    .build();

            activeMirrorTransformer = new Transformer.Builder(appContext)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            ReplaceActivePreviewedSelfie();
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException exception) {
                            LogException("MirrorSelfie", exception);
                            NotifyVideoEnhancementFailed(listener);
                            FinishActiveVideoEnhancement();
                        }
                    })
                    .build();

            activeMirrorTransformer.start(editedMediaItem, activeVideoEnhancement.transformedFile.getAbsolutePath());
            StartVideoEnhancementProgress(listener);
            return true;
        } catch (Exception ex) {
            LogException("MirrorSelfie", ex);
            FinishActiveVideoEnhancement();
            return false;
        }
    }

    public static boolean IsVideoEnhancementSupported() {
        return false;
    }

    public static boolean IsAudioNoiseReductionSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static boolean ReduceAudioNoise(Context context, RecordedVideo video,
                                           float attenuationDb,
                                           VideoEnhancementListener listener) {
        if (context != null) {
            SetContext(context);
        }
        if (appContext == null || video == null || !video.HasVideo()
                || !IsAudioNoiseReductionSupported()) {
            return false;
        }
        if (activeMirrorTransformer != null || activeVideoEnhancement != null || activeAudioEnhancement) {
            return false;
        }

        activeAudioEnhancement = true;
        MIRROR_COPY_EXECUTOR.execute(() -> {
            File audioVideoFile = null;
            RecordedVideo enhancedVideo = null;
            try {
                audioVideoFile = File.createTempFile("DVR_voice_", ".mp4", appContext.getCacheDir());
                Uri inputUri = video.uri != null ? video.uri : Uri.fromFile(new File(video.legacyPath));
                DroidAudioNoiseReducer.Process(appContext, inputUri, audioVideoFile, attenuationDb,
                        (percent, remainingSeconds) -> MIRROR_HANDLER.post(() ->
                                listener.OnVideoEnhancementProgress(percent, remainingSeconds)));
                if (video.uri != null) {
                    Uri enhancedUri = ReplaceMediaStoreVideo(
                            video.uri,
                            audioVideoFile,
                            video.displayName,
                            false);
                    enhancedVideo = new RecordedVideo(enhancedUri, null, video.displayName,
                            video.frontCamera, video.selfieAsPreviewed);
                } else {
                    String enhancedPath = CreateEnhancedLegacyVideoCopy(video, audioVideoFile);
                    enhancedVideo = new RecordedVideo(null, enhancedPath, video.displayName,
                            video.frontCamera, video.selfieAsPreviewed);
                }
            } catch (Exception ex) {
                LogException("AudioNoiseReducer", ex);
            } finally {
                if (audioVideoFile != null) {
                    audioVideoFile.delete();
                }
            }

            RecordedVideo finalEnhancedVideo = enhancedVideo;
            MIRROR_HANDLER.post(() -> {
                activeAudioEnhancement = false;
                if (finalEnhancedVideo != null && finalEnhancedVideo.HasVideo()) {
                    listener.OnVideoEnhanced(finalEnhancedVideo);
                } else {
                    NotifyVideoEnhancementFailed(listener);
                }
            });
        });
        return true;
    }

    private static void StartVideoEnhancement(RecordedVideo video, VideoEnhancementListener listener) {
        NotifyVideoEnhancementFailed(listener);
    }

    private static void ReplaceActivePreviewedSelfie() {
        PendingVideoEnhancement enhancement = activeVideoEnhancement;
        if (enhancement == null || enhancement.transformedFile == null) {
            FinishActiveVideoEnhancement();
            return;
        }

        MIRROR_COPY_EXECUTOR.execute(() -> {
            RecordedVideo enhancedVideo = null;
            try {
                if (enhancement.video.uri != null) {
                    Uri enhancedUri = ReplaceMediaStoreVideo(
                            enhancement.video.uri,
                            enhancement.transformedFile,
                            enhancement.video.displayName,
                            false);
                    enhancedVideo = new RecordedVideo(enhancedUri, null, enhancement.video.displayName,
                            true, true);
                } else {
                    String enhancedPath = CreateEnhancedLegacyVideoCopy(
                            enhancement.video,
                            enhancement.transformedFile,
                            ".selfie");
                    enhancedVideo = new RecordedVideo(null, enhancedPath, enhancement.video.displayName,
                            true, true);
                }
            } catch (Exception ex) {
                LogException("MirrorSelfie", ex);
            }

            RecordedVideo finalEnhancedVideo = enhancedVideo;
            MIRROR_HANDLER.post(() -> {
                if (finalEnhancedVideo != null && finalEnhancedVideo.HasVideo() && enhancement.listener != null) {
                    enhancement.listener.OnVideoEnhanced(finalEnhancedVideo);
                } else {
                    NotifyVideoEnhancementFailed(enhancement.listener);
                }
                FinishActiveVideoEnhancement();
            });
        });
    }

    private static void ReplaceActiveEnhancedVideo() {
        PendingVideoEnhancement enhancement = activeVideoEnhancement;
        if (enhancement == null || enhancement.transformedFile == null) {
            FinishActiveVideoEnhancement();
            return;
        }

        MIRROR_COPY_EXECUTOR.execute(() -> {
            RecordedVideo enhancedVideo = null;
            try {
                if (enhancement.video.uri != null) {
                    Uri enhancedUri = ReplaceMediaStoreVideo(
                            enhancement.video.uri,
                            enhancement.transformedFile,
                            enhancement.video.displayName,
                            false);
                    enhancedVideo = new RecordedVideo(enhancedUri, null, enhancement.video.displayName,
                            enhancement.video.frontCamera, enhancement.video.selfieAsPreviewed);
                } else {
                    String enhancedPath = CreateEnhancedLegacyVideoCopy(enhancement.video, enhancement.transformedFile);
                    enhancedVideo = new RecordedVideo(null, enhancedPath, enhancement.video.displayName,
                            enhancement.video.frontCamera, enhancement.video.selfieAsPreviewed);
                }
            } catch (Exception ex) {
                LogException("EnhanceVideo", ex);
            }

            RecordedVideo finalEnhancedVideo = enhancedVideo;
            MIRROR_HANDLER.post(() -> {
                if (finalEnhancedVideo != null && finalEnhancedVideo.HasVideo() && enhancement.listener != null) {
                    enhancement.listener.OnVideoEnhanced(finalEnhancedVideo);
                } else {
                    NotifyVideoEnhancementFailed(enhancement.listener);
                }
                FinishActiveVideoEnhancement();
            });
        });
    }

    private static String CreateEnhancedLegacyVideoCopy(RecordedVideo sourceVideo, File transformedFile) throws IOException {
        return CreateEnhancedLegacyVideoCopy(sourceVideo, transformedFile, ".enhanced");
    }

    private static String CreateEnhancedLegacyVideoCopy(RecordedVideo sourceVideo, File transformedFile,
                                                        String suffix) throws IOException {
        File source = new File(sourceVideo.legacyPath);
        File destination = CreateSiblingVideoFile(source, suffix);
        try (InputStream input = new FileInputStream(transformedFile);
             OutputStream output = new FileOutputStream(destination)) {
            Copy(input, output);
        }
        ScanLegacyVideo(destination.getAbsolutePath());
        return destination.getAbsolutePath();
    }

    private static File CreateSiblingVideoFile(File source, String suffix) {
        File parent = source.getParentFile();
        String name = source.getName();
        int extensionIndex = name.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        String extension = extensionIndex > 0 ? name.substring(extensionIndex) : ".mp4";
        File destination = new File(parent, baseName + suffix + extension);
        int copyIndex = 1;
        while (destination.exists()) {
            destination = new File(parent, baseName + suffix + "_" + copyIndex + extension);
            copyIndex++;
        }
        return destination;
    }

    private static void NotifyVideoEnhancementFailed(VideoEnhancementListener listener) {
        if (listener != null) {
            listener.OnVideoEnhancementFailed();
        }
    }

    private static void FinishActiveVideoEnhancement() {
        if (activeVideoEnhancement != null && activeVideoEnhancement.transformedFile != null) {
            activeVideoEnhancement.transformedFile.delete();
        }
        activeMirrorTransformer = null;
        activeVideoEnhancement = null;
        StartNextMirroredSelfie();
    }

    private static void StartVideoEnhancementProgress(VideoEnhancementListener listener) {
        final long startedAt = System.currentTimeMillis();
        MIRROR_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                if (activeMirrorTransformer == null || activeVideoEnhancement == null || listener == null) {
                    return;
                }

                int percent = -1;
                try {
                    int progressState = activeMirrorTransformer.getProgress(MIRROR_PROGRESS_HOLDER);
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        percent = Math.max(1, Math.min(99, MIRROR_PROGRESS_HOLDER.progress));
                    }
                } catch (IllegalStateException ex) {
                    LogException("MirrorSelfie", ex);
                }

                int remainingSeconds = -1;
                if (percent > 3) {
                    long elapsedMs = Math.max(1, System.currentTimeMillis() - startedAt);
                    long estimatedTotalMs = elapsedMs * 100L / percent;
                    remainingSeconds = (int) Math.max(1, (estimatedTotalMs - elapsedMs + 999) / 1000);
                }

                listener.OnVideoEnhancementProgress(percent > 0 ? percent : 1, remainingSeconds);
                MIRROR_HANDLER.postDelayed(this, 500);
            }
        });
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
        copyingMirroredSelfie = false;
        activeMirrorTransformer = null;
        activeMirroredSelfie = null;
        StartNextMirroredSelfie();
        if (activeMirrorTransformer == null && activeMirroredSelfie == null && PENDING_MIRRORED_SELFIES.isEmpty()) {
            pendingVideoProcessing = false;
        }
    }

    public static boolean HasPendingVideoProcessing() {
        return pendingVideoProcessing;
    }

    public static int GetVideoProcessingProgressPercent() {
        if (!pendingVideoProcessing) {
            return -1;
        }
        if (copyingMirroredSelfie) {
            return 96;
        }
        if (activeMirrorTransformer == null) {
            return -1;
        }

        try {
            int progressState = activeMirrorTransformer.getProgress(MIRROR_PROGRESS_HOLDER);
            if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                return Math.max(0, Math.min(99, MIRROR_PROGRESS_HOLDER.progress));
            }
        } catch (IllegalStateException ex) {
            LogException("MirrorSelfie", ex);
        }
        return -1;
    }

    private static void NotifyRecordedVideo(PendingMirroredSelfie selfie) {
        if (selfie != null) {
            NotifyRecordedVideo(selfie.listener,
                    new RecordedVideo(selfie.videoUri, selfie.legacyVideoPath, selfie.displayName, true, false));
        }
    }

    private static void NotifyRecordedVideo(RecordedVideoListener listener, RecordedVideo video) {
        if (listener != null && video != null && video.HasVideo()) {
            listener.OnRecordedVideoReady(video);
        }
    }

    private static class PendingMirroredSelfie {
        final Uri videoUri;
        final String legacyVideoPath;
        final String displayName;
        final RecordedVideoListener listener;
        File transformedFile;

        PendingMirroredSelfie(Uri videoUri, String legacyVideoPath, String displayName,
                              RecordedVideoListener listener) {
            this.videoUri = videoUri;
            this.legacyVideoPath = legacyVideoPath;
            this.displayName = displayName;
            this.listener = listener;
        }

        Uri GetInputUri() {
            return videoUri != null ? videoUri : Uri.fromFile(new File(legacyVideoPath));
        }
    }

    private static class PendingVideoEnhancement {
        final RecordedVideo video;
        final VideoEnhancementListener listener;
        File transformedFile;

        PendingVideoEnhancement(RecordedVideo video, VideoEnhancementListener listener) {
            this.video = video;
            this.listener = listener;
        }

        Uri GetInputUri() {
            return video.uri != null ? video.uri : Uri.fromFile(new File(video.legacyPath));
        }
    }

    private static void LogException(String tag, Exception ex) {
        String message = ex.getMessage();
        Log.e(tag, message != null ? message : ex.toString(), ex);
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

    public static void ReleaseLegacyPreviewCamera() {
        ResetRecord(false, null);
    }

    public static void ReleaseForServiceStop() {
        try {
            ResetRecord(false, null);
        } catch (Exception ex) {
            LogException("StopRecording", ex);
        }

        CloseCurrentVideoFile();
        ClearCurrentVideoState();
        currentPreviewWidth = 0;
        currentPreviewHeight = 0;
        currentCameraId = -1;
        mediaRecorderStarted = false;
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
            ResetRecord(false, null);
            return false;
        }
    }

    private static RecordedVideo ResetRecord(boolean record, RecordedVideoListener listener)
    {
        RecordedVideo recordedVideo = null;
        if (record && mMediaRecorder != null && mediaRecorderStarted) {
            try {
                mMediaRecorder.stop();
                recordedVideo = FinishCurrentVideoFile(listener);
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

        return recordedVideo;
    }

    public static void OnStopRecording(boolean record) {
        OnStopRecording(record, null);
    }

    public static RecordedVideo OnStopRecording(boolean record, RecordedVideoListener listener) {
        try {
            if (mServiceCamera != null) {
                mServiceCamera.reconnect();
            }
        } catch (IOException ex) {
            LogException("StopRecording", ex);
        }
        return ResetRecord(record, listener);
    }
}
