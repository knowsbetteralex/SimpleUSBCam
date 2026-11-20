package com.knowsbetteralex.simpleusbcam;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import com.jiangdg.ausbc.callback.IPreviewDataCallBack;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives real NV21 frames from AUSBC and renders a fast software fallback.
 * Unlike the old implementation, this does not JPEG-compress/decompress every
 * frame and does not impose a 230ms (4.3fps) display limit.
 *
 * Native TextureView is still available behind the software preview and can
 * be selected from the app's PREVIEW button for a direct-performance test.
 */
public final class LivePreview implements IPreviewDataCallBack {
    public interface FrameListener {
        void onFrameCount(int count);
    }

    private static final long FRAME_INTERVAL_MS = 33; // at most ~30 displayed fps
    private final ImageView output;
    private final FrameListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService conversion = Executors.newSingleThreadExecutor();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicInteger displayed = new AtomicInteger();
    private final AtomicBoolean converting = new AtomicBoolean(false);
    private volatile long openedAt = SystemClock.elapsedRealtime();
    private volatile long lastFrameAt;
    private volatile long lastQueuedAt;
    private volatile int generation = 0;
    private volatile boolean closed;
    private volatile boolean softwareEnabled = true;

    private int[] pixelBuffer;
    private int pixelWidth;
    private int pixelHeight;

    public LivePreview(ImageView output, FrameListener listener) {
        this.output = output;
        this.listener = listener;
    }

    public void setSoftwareEnabled(boolean enabled) {
        softwareEnabled = enabled;
        if (!enabled) {
            output.setVisibility(View.GONE);
            output.setImageDrawable(null);
        } else {
            // Clear any frame from an old preview mode and resume drawing.
            output.setVisibility(View.VISIBLE);
        }
    }

    public boolean isSoftwareEnabled() {
        return softwareEnabled;
    }

    public void reset() {
        generation++;
        count.set(0);
        displayed.set(0);
        lastFrameAt = 0;
        lastQueuedAt = 0;
        openedAt = SystemClock.elapsedRealtime();
        output.setImageDrawable(null);
        output.setVisibility(View.GONE);
    }

    @Override
    public void onPreviewData(byte[] data, int width, int height, DataFormat format) {
        if (closed || data == null || width <= 0 || height <= 0
                || format != DataFormat.NV21
                || data.length < (long) width * height * 3 / 2) return;

        final int seen = count.incrementAndGet();
        final long now = SystemClock.elapsedRealtime();
        lastFrameAt = now;
        if (seen == 1) {
            main.post(() -> {
                if (!closed && count.get() > 0) listener.onFrameCount(1);
            });
        }
        if (!softwareEnabled || now - lastQueuedAt < FRAME_INTERVAL_MS
                || !converting.compareAndSet(false, true)) return;

        lastQueuedAt = now;
        final int round = generation;
        // Copy on USB thread: AUSBC can reuse the supplied NV21 buffer.
        final byte[] snapshot = Arrays.copyOf(data, width * height * 3 / 2);
        try {
            conversion.execute(() -> convertAndDisplay(snapshot, width, height, round));
        } catch (RuntimeException ex) {
            converting.set(false);
        }
    }

    private void convertAndDisplay(byte[] nv21, int width, int height, int round) {
        Bitmap bitmap = null;
        try {
            if (closed || generation != round || !softwareEnabled) return;
            if (pixelBuffer == null || pixelWidth != width || pixelHeight != height) {
                pixelWidth = width;
                pixelHeight = height;
                pixelBuffer = new int[width * height];
            }

            // NV21: four Y values share one VU chroma pair (4:2:0).
            // Process in two-row blocks to avoid duplicate chroma calculation.
            int chromaBase = width * height;
            int[] pixels = pixelBuffer;
            for (int y = 0; y < height; y += 2) {
                int row0 = y * width;
                int row1 = Math.min(y + 1, height - 1) * width;
                int uvRow = chromaBase + (y / 2) * width;
                for (int x = 0; x < width; x += 2) {
                    int uv = uvRow + x;
                    if (uv + 1 >= nv21.length) continue;
                    int v = (nv21[uv] & 0xff) - 128;
                    int u = (nv21[uv + 1] & 0xff) - 128;
                    int rAdd = (1436 * v) >> 10;
                    int gAdd = -((352 * u + 731 * v) >> 10);
                    int bAdd = (1815 * u) >> 10;

                    int pos00 = row0 + x;
                    pixels[pos00] = toRgb((nv21[pos00] & 0xff), rAdd, gAdd, bAdd);
                    if (x + 1 < width) {
                        pixels[pos00 + 1] = toRgb(nv21[pos00 + 1] & 0xff, rAdd, gAdd, bAdd);
                    }
                    if (y + 1 < height) {
                        int pos10 = row1 + x;
                        pixels[pos10] = toRgb(nv21[pos10] & 0xff, rAdd, gAdd, bAdd);
                        if (x + 1 < width) {
                            pixels[pos10 + 1] = toRgb(nv21[pos10 + 1] & 0xff,
                                    rAdd, gAdd, bAdd);
                        }
                    }
                }
            }

            // RGB_565 halves per-frame Bitmap memory compared to ARGB_8888.
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            final Bitmap ready = bitmap;
            bitmap = null;
            main.post(() -> {
                try {
                    if (!closed && generation == round && softwareEnabled) {
                        output.setImageBitmap(ready);
                        output.setVisibility(View.VISIBLE);
                        displayed.incrementAndGet();
                    } else {
                        ready.recycle();
                    }
                } finally {
                    // Serialize decoding with UI consumption to prevent a
                    // backlog of huge Bitmap objects in the main message queue.
                    converting.set(false);
                }
            });
        } catch (RuntimeException ignored) {
            if (bitmap != null) bitmap.recycle();
            converting.set(false);
        }
    }

    private static int toRgb(int y, int rAdd, int gAdd, int bAdd) {
        int r = Math.max(0, Math.min(255, y + rAdd));
        int g = Math.max(0, Math.min(255, y + gAdd));
        int b = Math.max(0, Math.min(255, y + bAdd));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    public int getFrames() { return count.get(); }
    public int getDisplayedFrames() { return displayed.get(); }
    public long getOpenedAt() { return openedAt; }

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
