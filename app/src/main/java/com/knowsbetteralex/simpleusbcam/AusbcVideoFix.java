package com.knowsbetteralex.simpleusbcam;

import android.content.res.Configuration;
import android.util.Log;

import com.jiangdg.ausbc.MultiCameraClient;

import java.lang.reflect.Field;

/**
 * Workaround for AUSBC 3.6.0 H264EncodeProcessor:
 *
 * In NORMAL (NV21 / ByteBuffer) mode it rotates raw 640x480 data 90 degrees
 * on a portrait phone, but still feeds the result to a MediaCodec configured
 * for 640x480. After rotation the actual memory layout is 480x640. The
 * mismatched row stride causes tiled/interleaved artifacts in recorded MP4.
 *
 * Unlike view rotation, this is not a presentation transform: the bytes are
 * modified before H.264 encoding. Suppress the broken rotation and keep the
 * encoder dimensions aligned with the original camera frame. Do not touch the
 * UVC capture, preview, photo or audio path.
 *
 * This workaround accesses only fields of the bundled third-party library,
 * not hidden Android framework APIs. If a future AUSBC changes these fields,
 * recording is disabled instead of silently saving corrupted video.
 */
public final class AusbcVideoFix {
    private static final String TAG = "SimpleUSBCam";

    private AusbcVideoFix() { }

    public static Result apply(MultiCameraClient.ICamera camera) {
        if (camera == null) {
            return new Result(false, "Camera is null");
        }
        try {
            Field processField = MultiCameraClient.ICamera.class
                    .getDeclaredField("mVideoProcess");
            processField.setAccessible(true);
            Object processor = processField.get(camera);
            if (processor == null) {
                return new Result(false, "H264 processor not initialized");
            }
            if (!processor.getClass().getName().endsWith(".H264EncodeProcessor")) {
                return new Result(false, "Unexpected video processor: " +
                        processor.getClass().getName());
            }

            // Non-OpenGL mode feeds raw NV21 into MediaCodec, which expects the
            // original source dimensions. The library's conditional rotation
            // in portrait is wrong because its configured dimensions are not
            // swapped. Apply in portrait and landscape for consistency.
            Field rotateField = processor.getClass().getDeclaredField("isPortrait");
            rotateField.setAccessible(true);
            boolean wasRotating = rotateField.getBoolean(processor);
            rotateField.setBoolean(processor, false);
            if (rotateField.getBoolean(processor)) {
                return new Result(false, "Encoder rotation could not be disabled");
            }
            String detail = "Video H264: original frame geometry, " +
                    "AUSBC portrait rotation disabled" +
                    (wasRotating ? " (was ON)" : " (already OFF)");
            Log.i(TAG, detail);
            return new Result(true, detail);
        } catch (Exception ex) {
            Log.e(TAG, "AUSBC encoder geometry workaround failed", ex);
            return new Result(false,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    public static final class Result {
        public final boolean success;
        public final String detail;

        public Result(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }
    }
}
