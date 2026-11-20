package com.knowsbetteralex.simpleusbcam;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Moves finished AUSBC media from the app-private external directory into the
 * shared Android Gallery (MediaStore). A broken export never deletes the source.
 */
public final class GallerySaver {
    public interface Callback {
        void onResult(Uri galleryUri, String error);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private GallerySaver() { }

    public static void export(Context context, String sourcePath, boolean video,
                              Callback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                Uri saved = save(app, sourcePath, video);
                callback.onResult(saved, null);
            } catch (Exception ex) {
                callback.onResult(null, ex.getMessage());
            }
        });
    }

    private static Uri save(Context context, String path, boolean video) throws IOException {
        if (path == null || path.trim().isEmpty())
            throw new IOException("AUSBC не вернул путь к файлу");

        File source = new File(path);
        if (!source.isFile() || source.length() < 1)
            throw new IOException("Файл отсутствует или пуст: " + path);

        String date = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                Locale.US).format(new Date());
        String fileName = (video ? "VID_" : "IMG_") + "SimpleUSBCam_" + date
                + (video ? ".mp4" : ".jpg");
        String folder = video ? Environment.DIRECTORY_MOVIES
                : Environment.DIRECTORY_PICTURES;
        String mime = video ? "video/mp4" : "image/jpeg";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = video
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    folder + "/SimpleUSBCam");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = resolver.insert(collection, values);
            if (item == null)
                throw new IOException("MediaStore не создал запись");
            boolean complete = false;
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = resolver.openOutputStream(item, "w")) {
                    if (output == null) throw new IOException("Нет потока MediaStore");
                    copy(input, output);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(item, ready, null, null) != 1)
                    throw new IOException("MediaStore не подтвердил файл");
                complete = true;
            } finally {
                if (!complete) resolver.delete(item, null, null);
            }
            // Remove private duplicate only after successful publication.
            // If deletion fails, the gallery copy remains fully valid.
            source.delete();
            return item;
        }

        // Android 6-9: WRITE_EXTERNAL_STORAGE permission must be granted by
        // the activity before invoking this operation.
        File directory = new File(Environment.getExternalStoragePublicDirectory(folder),
                "SimpleUSBCam");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Не удалось создать папку " + directory);
        File target = new File(directory, fileName);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        } catch (IOException ex) {
            target.delete();
            throw ex;
        }
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()},
                new String[]{mime}, null);
        source.delete();
        return Uri.fromFile(target);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        out.flush();
    }
}
