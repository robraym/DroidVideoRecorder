package com.droid.videoRecorder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import com.kaleyra.noise_filter.DeepFilterNet;
import com.kaleyra.noise_filter.DefaultDeepFilterModelLoader;
import com.kaleyra.noise_filter.dispatcher.StandardDispatchers;
import com.rikorose.deepfilternet.NativeDeepFilterNet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

class DroidAudioNoiseReducer {
    interface ProgressListener {
        void OnProgress(int percent, int estimatedSecondsRemaining);
    }

    private static final int AUDIO_BITRATE = 96_000;
    private static final int OUTPUT_SAMPLE_RATE = 48_000;
    private static final int OUTPUT_CHANNEL_COUNT = 1;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int DEEP_FILTER_MODEL_TIMEOUT_SECONDS = 12;
    private static final String TAG = "AudioNoiseReducer";

    static void Process(Context context, Uri inputUri, File outputFile, float attenuationDb,
                        ProgressListener progressListener) throws Exception {
        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        NeuralNoiseSuppressor noiseSuppressor = null;

        try {
            Log.d(TAG, "Process start: inputUri=" + inputUri + ", output=" + outputFile.getAbsolutePath());
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(context, inputUri, null);
            int videoTrackIndex = FindTrack(videoExtractor, "video/");
            if (videoTrackIndex < 0) {
                throw new IllegalStateException("Video sem faixa de imagem");
            }
            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
            Log.d(TAG, "Video track format: " + videoFormat);
            int videoRotation = GetVideoRotation(context, inputUri);

            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(context, inputUri, null);
            int audioTrackIndex = FindTrack(audioExtractor, "audio/");
            if (audioTrackIndex < 0) {
                Log.d(TAG, "No audio track found. Copying video only.");
                CopyVideoOnly(videoExtractor, videoTrackIndex, videoFormat, outputFile, videoRotation);
                NotifyProgress(progressListener, 100, 0);
                return;
            }

            MediaFormat sourceAudioFormat = audioExtractor.getTrackFormat(audioTrackIndex);
            String audioMime = sourceAudioFormat.getString(MediaFormat.KEY_MIME);
            if (audioMime == null) {
                throw new IllegalStateException("Audio sem codec");
            }
            int sampleRate = sourceAudioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? sourceAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    : OUTPUT_SAMPLE_RATE;
            int channelCount = sourceAudioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? sourceAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    : OUTPUT_CHANNEL_COUNT;
            channelCount = Math.max(1, Math.min(2, channelCount));
            long durationUs = sourceAudioFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? sourceAudioFormat.getLong(MediaFormat.KEY_DURATION)
                    : 1L;
            Log.d(TAG, "Audio track format: " + sourceAudioFormat
                    + ", sampleRate=" + sampleRate
                    + ", channelCount=" + channelCount
                    + ", durationUs=" + durationUs);

            Log.d(TAG, "Audio noise reduction attenuation: " + attenuationDb + " dB");
            noiseSuppressor = NeuralNoiseSuppressor.Create(context.getApplicationContext(), attenuationDb);
            AudioPipeline audioPipeline = new AudioPipeline(noiseSuppressor, sampleRate, channelCount);
            AudioPtsTracker audioPtsTracker = new AudioPtsTracker();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            SetMuxerOrientation(muxer, videoRotation);
            int muxerVideoTrackIndex = muxer.addTrack(videoFormat);

            MediaFormat outputAudioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    OUTPUT_SAMPLE_RATE,
                    OUTPUT_CHANNEL_COUNT);
            outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);

            decoder = MediaCodec.createDecoderByType(audioMime);
            decoder.configure(sourceAudioFormat, null, null, 0);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(outputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            decoder.start();
            encoder.start();

            MediaCodec.BufferInfo decoderInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoderInfo = new MediaCodec.BufferInfo();

            audioExtractor.selectTrack(audioTrackIndex);
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean muxerStarted = false;
            int muxerAudioTrackIndex = -1;
            long startedAtMs = System.currentTimeMillis();

            while (!encoderDone) {
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(10_000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int sampleSize = audioExtractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long sampleTime = audioExtractor.getSampleTime();
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0);
                                audioExtractor.advance();
                            }
                        }
                    }
                }

                while (!decoderDone) {
                    int decoderOutputIndex = decoder.dequeueOutputBuffer(decoderInfo, 0);
                    if (decoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                    if (decoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat decoderFormat = decoder.getOutputFormat();
                        Log.d(TAG, "Decoder output format changed: " + decoderFormat);
                        if (decoderFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                && decoderFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                && audioPtsTracker.IsEmpty()) {
                            int decodedSampleRate = decoderFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            int decodedChannelCount = Math.max(1, Math.min(2,
                                    decoderFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)));
                            audioPipeline = new AudioPipeline(noiseSuppressor, decodedSampleRate, decodedChannelCount);
                        }
                        continue;
                    }
                    if (decoderOutputIndex >= 0) {
                        ByteBuffer decoderOutput = decoder.getOutputBuffer(decoderOutputIndex);
                        boolean endOfStream = (decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (decoderOutput != null && decoderInfo.size > 0) {
                            decoderOutput.position(decoderInfo.offset);
                            decoderOutput.limit(decoderInfo.offset + decoderInfo.size);
                            byte[] decodedPcm = new byte[decoderInfo.size];
                            decoderOutput.get(decodedPcm);
                            byte[] enhancedPcm = audioPipeline.Process(decodedPcm);
                            QueueEncoderInput(encoder, enhancedPcm, audioPtsTracker, false);
                            NotifyAudioProgress(progressListener, decoderInfo.presentationTimeUs, durationUs, startedAtMs);
                        }
                        decoder.releaseOutputBuffer(decoderOutputIndex, false);
                        if (endOfStream) {
                            byte[] remainingPcm = audioPipeline.Flush();
                            QueueEncoderInput(encoder, remainingPcm, audioPtsTracker, false);
                            QueueEncoderInput(encoder, new byte[0], audioPtsTracker, true);
                            decoderDone = true;
                            break;
                        }
                    }
                }

                while (true) {
                    int encoderOutputIndex = encoder.dequeueOutputBuffer(encoderInfo, 0);
                    if (encoderOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                    if (encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw new IllegalStateException("Formato de audio mudou mais de uma vez");
                        }
                        muxerAudioTrackIndex = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                        CopyVideoTrack(videoExtractor, videoTrackIndex, muxer, muxerVideoTrackIndex);
                        continue;
                    }
                    if (encoderOutputIndex >= 0) {
                        ByteBuffer encoderOutput = encoder.getOutputBuffer(encoderOutputIndex);
                        if (encoderOutput != null
                                && encoderInfo.size > 0
                                && muxerStarted
                                && (encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encoderOutput.position(encoderInfo.offset);
                            encoderOutput.limit(encoderInfo.offset + encoderInfo.size);
                            muxer.writeSampleData(muxerAudioTrackIndex, encoderOutput, encoderInfo);
                        }
                        encoderDone = (encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(encoderOutputIndex, false);
                        if (encoderDone) {
                            break;
                        }
                    }
                }
            }
            Log.d(TAG, "Process complete");
            NotifyProgress(progressListener, 100, 0);
        } catch (Exception ex) {
            Log.e(TAG, "Process failed", ex);
            throw ex;
        } finally {
            if (noiseSuppressor != null) {
                noiseSuppressor.Release();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                decoder.release();
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
                encoder.release();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                muxer.release();
            }
            if (videoExtractor != null) {
                videoExtractor.release();
            }
            if (audioExtractor != null) {
                audioExtractor.release();
            }
        }
    }

    private static int FindTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    private static int GetVideoRotation(Context context, Uri inputUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, inputUri);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation == null || rotation.length() == 0) {
                return 0;
            }
            return NormalizeRotation(Integer.parseInt(rotation));
        } catch (Exception ex) {
            Log.d(TAG, "Could not read video rotation: " + ex);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static int NormalizeRotation(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        return normalized == 90 || normalized == 180 || normalized == 270 ? normalized : 0;
    }

    private static void SetMuxerOrientation(MediaMuxer muxer, int rotation) {
        if (rotation == 0) {
            return;
        }

        try {
            muxer.setOrientationHint(rotation);
        } catch (Exception ex) {
            Log.d(TAG, "Could not set video rotation: " + ex);
        }
    }

    private static void CopyVideoOnly(MediaExtractor extractor, int videoTrackIndex,
                                      MediaFormat videoFormat, File outputFile,
                                      int videoRotation) throws Exception {
        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            SetMuxerOrientation(muxer, videoRotation);
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            muxer.start();
            CopyVideoTrack(extractor, videoTrackIndex, muxer, muxerVideoTrack);
        } finally {
            try {
                muxer.stop();
            } catch (Exception ignored) {
            }
            muxer.release();
        }
    }

    private static void CopyVideoTrack(MediaExtractor extractor, int videoTrackIndex,
                                       MediaMuxer muxer, int muxerVideoTrackIndex) {
        MediaFormat videoFormat = extractor.getTrackFormat(videoTrackIndex);
        extractor.selectTrack(videoTrackIndex);
        ByteBuffer buffer = ByteBuffer.allocate(GetTrackBufferSize(videoFormat));
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                break;
            }
            info.set(0, sampleSize, extractor.getSampleTime(), extractor.getSampleFlags());
            muxer.writeSampleData(muxerVideoTrackIndex, buffer, info);
            extractor.advance();
        }
        extractor.unselectTrack(videoTrackIndex);
    }

    private static int GetTrackBufferSize(MediaFormat format) {
        int defaultSize = 4 * 1024 * 1024;
        try {
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                return Math.max(defaultSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
        } catch (Exception ignored) {
        }
        return defaultSize;
    }

    private static void QueueEncoderInput(MediaCodec encoder, byte[] pcm, AudioPtsTracker ptsTracker,
                                          boolean endOfStream) {
        if (!endOfStream && pcm.length == 0) {
            return;
        }
        int offset = 0;
        do {
            int inputBufferIndex = encoder.dequeueInputBuffer(10_000);
            if (inputBufferIndex < 0) {
                continue;
            }
            ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
            if (inputBuffer == null) {
                throw new IllegalStateException("Encoder de audio sem input buffer");
            }
            inputBuffer.clear();
            int available = pcm.length - offset;
            int length = Math.min(inputBuffer.capacity(), available);
            length -= length % (BYTES_PER_SAMPLE * OUTPUT_CHANNEL_COUNT);
            if (!endOfStream && length <= 0) {
                break;
            }
            if (length > 0) {
                inputBuffer.put(pcm, offset, length);
                offset += length;
            }
            boolean finalBuffer = endOfStream && offset >= pcm.length;
            int flags = finalBuffer ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
            long presentationTimeUs = ptsTracker.GetPresentationTimeUs();
            encoder.queueInputBuffer(inputBufferIndex, 0, Math.max(0, length), presentationTimeUs, flags);
            ptsTracker.AdvanceBytes(length);
            if (finalBuffer || offset >= pcm.length) {
                break;
            }
        } while (offset < pcm.length || endOfStream);
    }

    private static void NotifyAudioProgress(ProgressListener listener, long presentationTimeUs,
                                            long durationUs, long startedAtMs) {
        int percent = Math.max(1, Math.min(99, Math.round(presentationTimeUs * 100f / Math.max(1, durationUs))));
        long elapsedMs = Math.max(1, System.currentTimeMillis() - startedAtMs);
        long estimatedTotalMs = elapsedMs * 100 / Math.max(1, percent);
        int remainingSeconds = (int) Math.max(0, (estimatedTotalMs - elapsedMs + 999) / 1000);
        NotifyProgress(listener, percent, remainingSeconds);
    }

    private static void NotifyProgress(ProgressListener listener, int percent, int remainingSeconds) {
        if (listener != null) {
            listener.OnProgress(percent, remainingSeconds);
        }
    }

    private static class AudioPtsTracker {
        private long writtenBytes = 0;

        boolean IsEmpty() {
            return writtenBytes == 0;
        }

        long GetPresentationTimeUs() {
            long samples = writtenBytes / (BYTES_PER_SAMPLE * OUTPUT_CHANNEL_COUNT);
            return samples * 1_000_000L / OUTPUT_SAMPLE_RATE;
        }

        void AdvanceBytes(int byteCount) {
            writtenBytes += Math.max(0, byteCount);
        }
    }

    private static class AudioPipeline {
        private final Pcm48kMonoConverter converter;
        private final NeuralNoiseSuppressor noiseSuppressor;

        AudioPipeline(NeuralNoiseSuppressor noiseSuppressor, int sourceSampleRate, int sourceChannelCount) {
            this.noiseSuppressor = noiseSuppressor;
            this.converter = new Pcm48kMonoConverter(sourceSampleRate, sourceChannelCount);
        }

        byte[] Process(byte[] decodedPcm) {
            return noiseSuppressor.Process(converter.Convert(decodedPcm), false);
        }

        byte[] Flush() {
            return noiseSuppressor.Process(new byte[0], true);
        }
    }

    private static class NeuralNoiseSuppressor {
        private final NativeDeepFilterNet model;
        private final int frameLength;
        private final byte[] pendingFrame;
        private final ByteBuffer modelBuffer;
        private int pendingSize = 0;
        private int processedFrameCount = 0;

        static NeuralNoiseSuppressor Create(Context context, float attenuationDb) throws Exception {
            Log.d(TAG, "Loading DeepFilterNet model");
            NativeDeepFilterNet model = new NativeDeepFilterNet(
                    context,
                    attenuationDb,
                    StandardDispatchers.INSTANCE,
                    new DefaultDeepFilterModelLoader());
            CountDownLatch modelLoadedLatch = new CountDownLatch(1);
            AtomicReference<DeepFilterNet> loadedModel = new AtomicReference<>();
            model.onModelLoaded(new Function1<DeepFilterNet, Unit>() {
                @Override
                public Unit invoke(DeepFilterNet deepFilterNet) {
                    loadedModel.set(deepFilterNet);
                    modelLoadedLatch.countDown();
                    return Unit.INSTANCE;
                }
            });

            boolean loaded = modelLoadedLatch.await(DEEP_FILTER_MODEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Log.d(TAG, "DeepFilterNet load result: loaded=" + loaded
                    + ", callbackModel=" + (loadedModel.get() != null)
                    + ", frameLength=" + model.getFrameLength());
            if (!loaded
                    || loadedModel.get() == null
                    || model.getFrameLength() <= 0) {
                model.release();
                throw new IllegalStateException("Modelo neural de audio nao carregou");
            }

            model.setAttenuationLimit(attenuationDb);
            Log.d(TAG, "DeepFilterNet ready: frameLength=" + model.getFrameLength());
            return new NeuralNoiseSuppressor(model, (int) model.getFrameLength());
        }

        private NeuralNoiseSuppressor(NativeDeepFilterNet model, int frameLength) {
            this.model = model;
            this.frameLength = Math.max(BYTES_PER_SAMPLE, frameLength);
            this.pendingFrame = new byte[this.frameLength];
            this.modelBuffer = ByteBuffer.allocateDirect(this.frameLength);
            this.modelBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        byte[] Process(byte[] pcm48kMono, boolean flush) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(pcm48kMono.length + frameLength);
            int offset = 0;
            while (offset < pcm48kMono.length) {
                int bytesToCopy = Math.min(frameLength - pendingSize, pcm48kMono.length - offset);
                System.arraycopy(pcm48kMono, offset, pendingFrame, pendingSize, bytesToCopy);
                pendingSize += bytesToCopy;
                offset += bytesToCopy;

                if (pendingSize == frameLength) {
                    ProcessCurrentFrame(output, frameLength);
                    pendingSize = 0;
                }
            }

            if (flush && pendingSize > 0) {
                int validBytes = pendingSize;
                Arrays.fill(pendingFrame, pendingSize, frameLength, (byte) 0);
                ProcessCurrentFrame(output, validBytes);
                pendingSize = 0;
            }
            return output.toByteArray();
        }

        void Release() {
            model.release();
        }

        private void ProcessCurrentFrame(ByteArrayOutputStream output, int validBytes) {
            modelBuffer.clear();
            modelBuffer.put(pendingFrame, 0, frameLength);
            modelBuffer.flip();
            float result = model.processFrame(modelBuffer);
            processedFrameCount++;
            if (processedFrameCount <= 5 || processedFrameCount % 100 == 0) {
                Log.d(TAG, "Processed frame " + processedFrameCount
                        + ": validBytes=" + validBytes
                        + ", frameLength=" + frameLength
                        + ", modelResult=" + result);
            }
            modelBuffer.rewind();
            modelBuffer.get(pendingFrame, 0, frameLength);
            output.write(pendingFrame, 0, validBytes);
        }
    }

    private static class Pcm48kMonoConverter {
        private final int sourceSampleRate;
        private final int sourceChannelCount;
        private long sourceFrameOffset = 0;
        private double nextOutputSourceFrame = 0;

        Pcm48kMonoConverter(int sourceSampleRate, int sourceChannelCount) {
            this.sourceSampleRate = Math.max(8_000, sourceSampleRate);
            this.sourceChannelCount = Math.max(1, Math.min(2, sourceChannelCount));
        }

        byte[] Convert(byte[] sourcePcm) {
            int bytesPerSourceFrame = BYTES_PER_SAMPLE * sourceChannelCount;
            int sourceFrames = sourcePcm.length / bytesPerSourceFrame;
            if (sourceFrames <= 0) {
                return new byte[0];
            }

            short[] monoSamples = new short[sourceFrames];
            for (int frame = 0; frame < sourceFrames; frame++) {
                int frameOffset = frame * bytesPerSourceFrame;
                int sum = 0;
                for (int channel = 0; channel < sourceChannelCount; channel++) {
                    int sampleOffset = frameOffset + channel * BYTES_PER_SAMPLE;
                    sum += ReadShortLe(sourcePcm, sampleOffset);
                }
                monoSamples[frame] = (short) (sum / sourceChannelCount);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.max(BYTES_PER_SAMPLE, sourceFrames * OUTPUT_SAMPLE_RATE / sourceSampleRate * BYTES_PER_SAMPLE));
            double step = sourceSampleRate / (double) OUTPUT_SAMPLE_RATE;
            double endFrame = sourceFrameOffset + sourceFrames - 1;
            while (nextOutputSourceFrame <= endFrame) {
                double localPosition = nextOutputSourceFrame - sourceFrameOffset;
                int index = Math.max(0, Math.min(sourceFrames - 1, (int) Math.floor(localPosition)));
                int nextIndex = Math.min(sourceFrames - 1, index + 1);
                double fraction = localPosition - index;
                short sample = (short) Math.round(monoSamples[index] * (1d - fraction)
                        + monoSamples[nextIndex] * fraction);
                WriteShortLe(output, sample);
                nextOutputSourceFrame += step;
            }
            sourceFrameOffset += sourceFrames;
            return output.toByteArray();
        }

        private static short ReadShortLe(byte[] data, int offset) {
            return (short) ((data[offset] & 0xff) | (data[offset + 1] << 8));
        }

        private static void WriteShortLe(ByteArrayOutputStream output, short sample) {
            output.write(sample & 0xff);
            output.write((sample >> 8) & 0xff);
        }
    }
}
