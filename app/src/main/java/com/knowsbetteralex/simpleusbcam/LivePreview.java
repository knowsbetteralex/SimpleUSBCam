package com.knowsbetteralex.simpleusbcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import com.jiangdg.ausbc.callback.IPreviewDataCallBack;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evidence-based preview: AUSBC OPENED alone means native startPreview() ran,
 * not that any frames actually reached Java. This callback is invoked only for
 * genuine NV21 frames. Draw a low-fps software backup on top of the TextureView.
 * Once the UVC transfer works, MP4 can use AUSBC's original high-speed path.
 */
public final class LivePreview implements IPreviewDataCallBack {
    public interface FrameListener {
        void onFrameCount(int count);
    }

    private final ImageView output;
    private final FrameListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService conversion = Executors.newSingleThreadExecutor();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicBoolean converting = new AtomicBoolean(false);

    private volatile long openedAt = SystemClock.elapsedRealtime();
    private volatile long lastFrameAt;
    private volatile long lastConvertedAt;
    private volatile int generation = 0;
    private volatile boolean closed;

    public LivePreview(ImageView output, FrameListener listener) {
        this.output = output;
        this.listener = listener;
    }

    public void reset() {
        generation++;
        count.set(0);
        lastFrameAt = 0;
        lastConvertedAt = 0;
        openedAt = SystemClock.elapsedRealtime();
        output.setImageDrawable(null);
        output.setVisibility(View.GONE);
    }

    @Override
    public void onPreviewData(byte[] data, int width, int height, DataFormat format) {
        if (closed || data == null || width <= 0 || height <= 0 ||
                format != DataFormat.NV21 ||
                data.length < (long) width * height * 3 / 2) {
            return;
        }
        final int seen = count.incrementAndGet();
        final long now = SystemClock.elapsedRealtime();
        lastFrameAt = now;
        if (seen == 1) main.post(() -> {
            if (!closed && count.get() > 0) listener.onFrameCount(1);
        });

        if (now - lastConvertedAt < 230 || !converting.compareAndSet(false, true))
            return;
        lastConvertedAt = now;
        final int round = generation;
        // UVC reuses its buffer; preserve a copy only for the software fallback.
        byte[] copy = Arrays.copyOf(data, width * height * 3 / 2);
        conversion.execute(() -> {
            try {
                YuvImage yuv = new YuvImage(copy, ImageFormat.NV21, width, height, null);
                ByteArrayOutputStream jpg = new ByteArrayOutputStream(80_000);
                if (yuv.compressToJpeg(new Rect(0, 0, width, height), 80, jpg)) {
                    byte[] bytes = jpg.toByteArray();
                    Bitmap frame = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (frame != null) main.post(() -> {
                        if (closed || round != generation) {
                            frame.recycle();
                            return;
                        }
                        output.setImageBitmap(frame);
                        output.setVisibility(View.VISIBLE);
                    });
                }
            } catch (RuntimeException ignored) {
                // JPEG fallback is diagnostic; it must never stop UVC capture.
            } finally {
                converting.set(false);
            }
        });
    }

    public int getFrames() {
        return count.get();
    }

    public long getOpenedAt() {
        return openedAt;
    }

    public long getLastFrameAgeMs() {
        return lastFrameAt == 0 ? -1 : SystemClock.elapsedRealtime() - lastFrameAt;
    }

    public void close() {
        closed = true;
        generation++;
        conversion.shutdownNow();
        main.post(() -> {
            output.setImageDrawable(null);
            output.setVisibility(View.GONE);
        });
    }
}
