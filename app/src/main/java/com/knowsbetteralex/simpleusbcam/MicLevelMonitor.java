package com.knowsbetteralex.simpleusbcam;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Live microphone level without competing with the recorder.
 *
 * Idle:   read PCM directly from AudioRecord.
 * Record: AUSBC owns the single microphone AudioRecord. We decode a COPY
 *         of its AAC output to measure the sound captured in the MP4.
 * Only the level is emitted; none of the captured audio is stored here.
 */
public final class MicLevelMonitor {
    public interface Listener {
        void onLevel(float normalizedLevel);
    }

    private static final int SAMPLE_RATE = 8000; // AUSBC AudioStrategySystem
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<byte[]> aacQueue = new ArrayBlockingQueue<>(24);
    private volatile AudioRecord microphone;
    private volatile boolean idleRunning;
    private volatile boolean decoding;
    private Thread idleThread;
    private Thread decoderThread;
    private volatile long lastUiTime;

    public MicLevelMonitor(Listener listener) {
        this.listener = listener;
    }

    public synchronized void startIdle() {
        stopDecoder();
        if (idleRunning) return;
        idleRunning = true;
        idleThread = new Thread(this::runIdle, "Mic-level-PCM");
        idleThread.start();
    }

    private void runIdle() {
        AudioRecord ar = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) return;
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, 2048));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) return;
            microphone = ar;
            ar.startRecording();
            byte[] pcm = new byte[2048];
            while (idleRunning) {
                int count = ar.read(pcm, 0, pcm.length);
                if (count > 1) emitFromPcm(pcm, count);
                else if (count < 0) break;
            }
        } catch (Exception ignored) {
            // Permission denial, mic busy or missing input: level unavailable.
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) { }
                ar.release();
            }
            if (microphone == ar) microphone = null;
        }
    }

    public synchronized void stopIdle() {
        idleRunning = false;
        AudioRecord ar = microphone;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) { }
        }
        // The worker releases AudioRecord in its finally block.
    }

    public synchronized void startDecoder() {
        stopIdle();
        if (decoding) return;
        decoding = true;
        aacQueue.clear();
        decoderThread = new Thread(this::runDecoder, "Mic-level-AAC");
        decoderThread.start();
    }

    /** Invoked by the AUSBC encoded stream callback; never blocks encoding. */
    public void offerAac(ByteBuffer encoded, int offset, int size) {
        if (!decoding || size <= 0 || size > 8192) return;
        try {
            ByteBuffer copy = encoded.duplicate();
            if (offset < 0 || offset + size > copy.limit()) return;
            byte[] payload = new byte[size];
            copy.position(offset);
            copy.get(payload);
            if (!aacQueue.offer(payload)) {
                aacQueue.poll();
                aacQueue.offer(payload);
            }
        } catch (Exception ignored) { }
    }

    private void runDecoder() {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2); // AAC LC
            // AudioSpecificConfig: AAC LC, 8000 Hz (index 11), mono.
            format.setByteBuffer("csd-0",
                    ByteBuffer.wrap(new byte[] {(byte) 0x15, (byte) 0x88}));
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ptsUs = 0;
            while (decoding) {
                byte[] frame = aacQueue.poll(120, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input != null && input.capacity() >= frame.length) {
                            input.clear();
                            input.put(frame);
                            codec.queueInputBuffer(inputIndex, 0, frame.length,
                                    ptsUs, 0);
                            ptsUs += 128_000; // 1024 AAC samples / 8 kHz
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
                        }
                    }
                }

                for (int n = 0; n < 8; n++) {
                    int outputIndex = codec.dequeueOutputBuffer(info, 0);
                    if (outputIndex < 0) break;
                    ByteBuffer output = codec.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 1) {
                        ByteBuffer pcm = output.duplicate()
                                .order(ByteOrder.LITTLE_ENDIAN);
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        emitFromShorts(pcm);
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
        } catch (Exception ignored) {
            // An AAC decoder may not be available on a particular phone.
            // Recording itself is performed by AUSBC and is not affected.
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
        }
    }

    public synchronized void stopDecoder() {
        decoding = false;
        aacQueue.clear();
        if (decoderThread != null) decoderThread.interrupt();
    }

    public synchronized void stopAll() {
        stopIdle();
        stopDecoder();
        main.post(() -> listener.onLevel(0f));
    }

    private void emitFromPcm(byte[] data, int size) {
        double sum = 0;
        int samples = 0;
        for (int n = 0; n + 1 < size; n += 2) {
            int s = (short) ((data[n] & 0xff) | (data[n + 1] << 8));
            sum += (double) s * s;
            samples++;
        }
        if (samples > 0) emit(Math.sqrt(sum / samples) / 32768.0);
    }

    private void emitFromShorts(ByteBuffer pcm) {
        double sum = 0;
        int count = 0;
        while (pcm.remaining() >= 2) {
            int value = pcm.getShort();
            sum += (double) value * value;
            count++;
        }
        if (count > 0) emit(Math.sqrt(sum / count) / 32768.0);
    }

    private void emit(double rms) {
        long now = SystemClock.uptimeMillis();
        if (now - lastUiTime < 75) return;
        lastUiTime = now;
        // Visual meter maps -60 dBFS to 0 and 0 dBFS to 100%.
        double db = 20 * Math.log10(Math.max(.000001, rms));
        float level = (float) Math.max(0.0, Math.min(1.0, (db + 60) / 60));
        main.post(() -> listener.onLevel(level));
    }
}
