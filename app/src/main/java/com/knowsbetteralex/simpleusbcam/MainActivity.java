package com.knowsbetteralex.simpleusbcam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import com.jiangdg.ausbc.callback.IPreviewDataCallBack;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import android.app.AlertDialog;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.callback.IEncodeDataCallBack;
import com.jiangdg.ausbc.render.env.RotateType;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;

/**
 * Small Java UI on top of AUSBC. Video and AAC are muxed by the library;
 * the meter decodes a COPY of AAC while recording, so there is only one
 * active microphone capture.
 */
public final class MainActivity extends CameraActivity {
    private static final int PERMISSION_REQUEST = 100;
    private static final String USB_ACTION = "com.knowsbetteralex.simpleusbcam.USB_PERMISSION";
    private static final String TAG = "SimpleUSBCam";
    private static final int ENDO_VID = 0x349c;
    private static final int ENDO_PID = 0x0411;
    private static final int BG = 0xff10161c;
    private static final int SURFACE = 0xff1c2933;
    private static final int WHITE = 0xffebf4f8;
    private static final int GREEN = 0xff39d6a3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout cameraContainer;
    private AspectRatioTextureView cameraView;
    private ProgressBar meterBar;
    private TextView meterLabel;
    private TextView status;
    private TextView timeLabel;
    private Button recordButton;
    private Button micButton;
    private TextView usbDetails;
    private boolean usbPermissionPending;
    private boolean usbReceiverRegistered;
    private boolean pendingMicChange;
    private final StringBuilder trace = new StringBuilder();
    private MicLevelMonitor meter;
    private boolean foreground = false;
    private boolean recording = false;
    private boolean micEnabled = true;
    private int rotation = 0;
    private long startedAt;
    private long lastCableButtonAt;
    private Object uvcButtonCallback;
    private LivePreview livePreview;
    private ImageView previewOverlay;
    private Button videoModeButton;
    private Button previewModeButton;
    private int fpsPreviousFrames;
    private int fpsPreviousDisplayed;
    private long fpsLastAt;
    private float cameraFps;
    private float screenFps;
    private long recordBeganAt;
    private boolean recordingActive;
    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            if (livePreview != null) {
                calculateFps();
                updateUsbDetails();
                if (isCameraOpened() && livePreview.getFrames() == 0
                        && SystemClock.elapsedRealtime() - livePreview.getOpenedAt() > 3000
                        && !recording) {
                    showStatus("UVC открыт, но кадры не поступают. Выберите YUYV и переподключите USB.");
                }
            }
            ui.postDelayed(this, 900);
        }
    };
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!USB_ACTION.equals(intent.getAction())) return;
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            usbPermissionPending = false;
            boolean granted = device != null && intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
            addTrace("USB permission callback: " + (granted ? "GRANTED" : "DENIED"));
            if (granted) {
                showStatus("USB-разрешение получено, открываем видеопоток...");
                ui.postDelayed(() -> requestPermission(device), 350);
            } else showStatus("Доступ к USB не предоставлен; нажмите USB");
            updateUsbDetails();
        }
    };

    private final Runnable timeTick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long sec = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            timeLabel.setText(String.format(java.util.Locale.US,
                    "%02d:%02d", sec / 60, sec % 60));
            ui.postDelayed(this, 300);
        }
    };

    @Override
    protected View getRootView(LayoutInflater inflater) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        micEnabled = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("mic", true);
        meter = new MicLevelMonitor(level -> {
            if (meterBar != null) {
                meterBar.setProgress((int) (100 * level));
                meterLabel.setText(String.format(java.util.Locale.US,
                        "%d %%", Math.round(level * 100)));
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(9));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("SimpleUSBCam", 18, WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1));
        timeLabel = text("00:00", 16, GREEN);
        header.addView(timeLabel);
        Button usbButton = button("USB", SURFACE);
        header.addView(usbButton, new LinearLayout.LayoutParams(dp(62), dp(37)));
        usbButton.setOnClickListener(v -> {
            addTrace("Manual USB reconnect requested");
            retryUsb(true);
        });
        root.addView(header);

        cameraContainer = new FrameLayout(this);
        cameraContainer.setBackgroundColor(Color.BLACK);
        cameraView = new AspectRatioTextureView(this);
        // AUSBC attaches the native TextureView in CameraActivity.initView().
        root.addView(cameraContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = text("Подключите USB-эндоскоп", 13, 0xffa3b2bc);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        root.addView(status, slp);
        usbDetails = text("Поиск USB...", 11, 0xff8fa5b1);
        usbDetails.setOnClickListener(v -> showDiagnostics());
        LinearLayout formatRow = new LinearLayout(this);
        formatRow.setGravity(Gravity.CENTER_VERTICAL);
        formatRow.addView(usbDetails, new LinearLayout.LayoutParams(0, dp(34), 1));
        videoModeButton = button("MJPEG", SURFACE);
        videoModeButton.setTextSize(11);
        formatRow.addView(videoModeButton,
                new LinearLayout.LayoutParams(dp(93), dp(34)));
        videoModeButton.setOnClickListener(v -> chooseVideoMode());
        previewModeButton = button("ПРОГ", SURFACE);
        previewModeButton.setTextSize(10);
        formatRow.addView(previewModeButton,
                new LinearLayout.LayoutParams(dp(65), dp(34)));
        previewModeButton.setOnClickListener(v -> togglePreviewMode());
        root.addView(formatRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView audioCaption = text("MIC", 12, GREEN);
        audioRow.addView(audioCaption);
        meterBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        meterBar.setMax(100);
        meterBar.setProgressTintList(ColorStateList.valueOf(GREEN));
        meterBar.setProgressBackgroundTintList(
                ColorStateList.valueOf(0xff354651));
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(
                0, dp(14), 1);
        meterLp.setMargins(dp(9), 0, dp(10), 0);
        audioRow.addView(meterBar, meterLp);
        meterLabel = text("0 %", 12, WHITE);
        meterLabel.setMinWidth(dp(57));
        audioRow.addView(meterLabel);
        root.addView(audioRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button photo = button("Фото", SURFACE);
        recordButton = button("● Запись", 0xffb53d4a);
        micButton = button("Микрофон: ВКЛ", SURFACE);
        Button rotate = button("↻ 90°", SURFACE);
        addButton(controls, photo, 1.0f);
        addButton(controls, recordButton, 1.25f);
        addButton(controls, micButton, 1.5f);
        addButton(controls, rotate, 1.0f);
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(55)));

        photo.setOnClickListener(v -> takePhoto());
        recordButton.setOnClickListener(v -> {
            if (recording) stopVideo(); else startVideo();
        });
        micButton.setOnClickListener(v -> toggleMicrophone());
        rotate.setOnClickListener(v -> {
            rotation = (rotation + 1) % 4;
            cameraView.setRotation(rotation * 90f);
            if (previewOverlay != null) previewOverlay.setRotation(rotation * 90f);
        });
        return root;
    }

    @Override protected IAspectRatio getCameraView() {
        return cameraView;
    }

    @Override protected ViewGroup getCameraViewContainer() {
        return cameraContainer;
    }

    @Override protected CameraRequest getCameraRequest() {
        int mode = getSharedPreferences("settings", MODE_PRIVATE).getInt("video_mode", 0);
        int width = mode == 2 || mode == 3 ? 320 : 640;
        int height = mode == 2 || mode == 3 ? 240 : 480;
        return new CameraRequest.Builder()
                .setPreviewWidth(width)
                .setPreviewHeight(height)
                .setRenderMode(CameraRequest.RenderMode.NORMAL)
                .setPreviewFormat(mode == 1 || mode == 3
                        ? CameraRequest.PreviewFormat.FORMAT_YUYV
                        : CameraRequest.PreviewFormat.FORMAT_MJPEG)
                .setAudioSource(micEnabled
                        ? CameraRequest.AudioSource.SOURCE_SYS_MIC
                        : CameraRequest.AudioSource.NONE)
                .setAspectRatioShow(true)
                .create();
    }

    @Override protected UsbDevice getDefaultCamera() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() == ENDO_VID &&
                    device.getProductId() == ENDO_PID) return device;
        }
        return null;
    }

    @Override protected void initData() {
        micButton.setText(micEnabled ? "Микрофон: ВКЛ" : "Микрофон: ВЫКЛ");
        registerOwnUsbReceiver();
        updateVideoModeButton();
        previewOverlay = new ImageView(this);
        previewOverlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewOverlay.setVisibility(View.GONE);
        cameraContainer.addView(previewOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        livePreview = new LivePreview(previewOverlay, result -> {
            if (result == 1) {
                addTrace("FIRST FRAME received");
                showStatus("Видеокадры поступают • " +
                        (livePreview.isSoftwareEnabled() ? "программный" : "аппаратный") +
                        " предпросмотр");
            }
        });
        livePreview.setSoftwareEnabled(getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("software_preview", true));
        updatePreviewModeButton();
        recordButton.setEnabled(false);
        ui.post(previewTick);
        requestAppPermissions();
        ui.postDelayed(() -> retryUsb(false), 2200);
    }

    private void requestAppPermissions() {
        java.util.ArrayList<String> required = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!required.isEmpty()) {
            requestPermissions(required.toArray(new String[0]), PERMISSION_REQUEST);
        } else if (foreground && micEnabled && !recording) {
            meter.startIdle();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,
                    String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            showStatus("Разрешите доступ к камере Android");
        } else {
            ui.postDelayed(() -> retryUsb(false), 600);
        }
        if (micEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED && foreground)
            meter.startIdle();
        else if (micEnabled) showStatus("Без разрешения на микрофон звук не запишется");
    }

    @Override public void onCameraState(MultiCameraClient.ICamera camera,
            ICameraStateCallBack.State state, String message) {
        runOnUiThread(() -> {
            switch (state) {
                case OPENED:
                    pendingMicChange = false;
                    recordButton.setEnabled(false);
                    fpsPreviousFrames = 0;
                    fpsPreviousDisplayed = 0;
                    fpsLastAt = 0;
                    cameraFps = 0;
                    screenFps = 0;
                    livePreview.reset();
                    camera.addPreviewDataCallBack(livePreview);
                    addTrace("UVC OPENED: " + camera.getCameraRequest().getPreviewWidth()
                            + "x" + camera.getCameraRequest().getPreviewHeight()
                            + " previewFormat=" + camera.getCameraRequest().getPreviewFormat());
                    addTrace("UVC supported sizes: " + getAllPreviewSizes(null));
                    // Avoid native UVC button proxy until the JNI lifetime is verified.
                    setEncodeDataCallBack((type, data, offset, size, pts) -> {
                        if (type == IEncodeDataCallBack.DataType.AAC && recording)
                            meter.offerAac(data, offset, size);
                    });
                    cameraView.setRotation(rotation * 90f);
                    showStatus("UVC открыт • ожидаем первые видеокадры...");
                    ui.postDelayed(() -> {
                        if (isCameraOpened() && livePreview.getFrames() > 0 && !pendingMicChange)
                            recordButton.setEnabled(true);
                    }, 500);
                    updateUsbDetails();
                    break;
                case CLOSED:
                    addTrace("UVC CLOSED");
                    camera.removePreviewDataCallBack(livePreview);
                    livePreview.reset();
                    recordButton.setEnabled(false);
                    uvcButtonCallback = null;
                    if (recording) stopVideo();
                    showStatus("USB-камера отключена");
                    break;
                case ERROR:
                    addTrace("UVC ERROR: " + message);
                    recordButton.setEnabled(false);
                    livePreview.reset();
                    if (recording) stopVideo();
                    showStatus("Ошибка USB: " + (message == null ? "неизвестно" : message));
                    updateUsbDetails();
                    break;
                default: break;
            }
        });
    }

    private ICaptureCallBack captureCallback(String kind) {
        final AtomicBoolean galleryExportStarted = new AtomicBoolean(false);
        return new ICaptureCallBack() {
            @Override public void onBegin() {
                ui.post(() -> {
                    addTrace(kind + " onBegin");
                    if (recording && "Видео".equals(kind)) {
                        recordingActive = true;
                        recordBeganAt = SystemClock.elapsedRealtime();
                        startedAt = recordBeganAt;
                        recordButton.setText("■ Стоп");
                        recordButton.setEnabled(true);
                        ui.post(timeTick);
                    }
                    showStatus(kind + ": сохранение началось");
                });
            }
            @Override public void onError(String error) {
                ui.post(() -> {
                    addTrace(kind + " onError: " + error);
                    showStatus(kind + ": " + (error == null ? "ошибка" : error));
                    if (recording && "Видео".equals(kind)) finishRecordingUi();
                });
            }
            @Override public void onComplete(String path) {
                if (!galleryExportStarted.compareAndSet(false, true)) return;
                ui.post(() -> {
                    showStatus(kind + ": добавляем в галерею...");
                    if (recording && "Видео".equals(kind)) finishRecordingUi();
                });
                GallerySaver.export(MainActivity.this, path, "Видео".equals(kind),
                        (uri, error) -> ui.post(() -> {
                            if (error == null) {
                                showStatus(kind + " в галерее • SimpleUSBCam");
                                addTrace(kind + " saved in MediaStore: " + uri);
                                Toast.makeText(MainActivity.this,
                                        kind + " сохранено в галерею", Toast.LENGTH_LONG).show();
                            } else {
                                showStatus(kind + " сохранено в папке приложения; " +
                                        "ошибка галереи: " + error);
                                addTrace("Gallery export error: " + error + " source=" + path);
                            }
                        }));
            }
        };
    }

    private void takePhoto() {
        if (!isCameraOpened()) {
            showStatus("Сначала подключите эндоскоп");
            return;
        }
        captureImage(captureCallback("Фото"), null);
    }

    private void startVideo() {
        if (livePreview == null || livePreview.getFrames() < 3
                || livePreview.getLastFrameAgeMs() > 2500) {
            showStatus("Нет живых кадров. MP4 пока записывать нельзя.");
            addTrace("Recording blocked: no recent decoded video frames");
            return;
        }
        if (pendingMicChange) {
            showStatus("Переподключите эндоскоп, чтобы применить режим микрофона");
            return;
        }
        if (!isCameraOpened()) {
            showStatus("Сначала подключите эндоскоп");
            return;
        }
        if (micEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showStatus("Для записи звука нужно разрешение микрофона");
            requestAppPermissions();
            return;
        }
        recording = true;
        recordingActive = false;
        startedAt = SystemClock.elapsedRealtime();
        recordButton.setText("Подготовка...");
        recordButton.setEnabled(false);
        recordButton.setBackgroundTintList(ColorStateList.valueOf(0xffa02f38));
        micButton.setEnabled(false);
        ui.removeCallbacks(timeTick);
        meter.stopIdle();
        if (micEnabled) meter.startDecoder();
        try {
            captureVideoStart(captureCallback("Видео"), null, 0L);
            ui.postDelayed(() -> {
                if (recording && !recordingActive) {
                    addTrace("Muxer did not report onBegin after 8s");
                    showStatus("Нет запуска MP4. Проверьте диагностику; повторите с другим форматом.");
                    // Do not call captureVideoStop: unstarted muxer throws
                    // Can't stop due to wrong state(INITIALIZED).
                }
            }, 8000);
            showStatus(micEnabled ? "Запись видео + микрофон телефона" :
                    "Запись видео без звука");
        } catch (Exception ex) {
            showStatus("Не удалось начать запись: " + ex.getMessage());
            finishRecordingUi();
        }
    }

    private void stopVideo() {
        if (!recording) return;
        if (!recordingActive) {
            showStatus("MP4 ещё не начался: ждём видеокодек");
            return;
        }
        captureVideoStop();
        finishRecordingUi();
        showStatus("Сохраняем MP4...");
    }

    private void finishRecordingUi() {
        recording = false;
        recordingActive = false;
        ui.removeCallbacks(timeTick);
        recordButton.setText("● Запись");
        recordButton.setEnabled(livePreview != null && livePreview.getFrames() > 0
                && !pendingMicChange);
        recordButton.setBackgroundTintList(ColorStateList.valueOf(0xffb53d4a));
        micButton.setEnabled(true);
        meter.stopDecoder();
        if (foreground && micEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED)
            ui.postDelayed(() -> {
                if (!recording && foreground && micEnabled) meter.startIdle();
            }, 700);
    }

    private void toggleMicrophone() {
        if (recording) return;
        micEnabled = !micEnabled;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("mic", micEnabled).apply();
        micButton.setText(micEnabled ? "Микрофон: ВКЛ" : "Микрофон: ВЫКЛ");

        if (micEnabled) requestAppPermissions();
        else {
            meter.stopAll();
            meterBar.setProgress(0);
        }
        // Native UVC camera teardown/reopen in the same click caused a crash
        // on the user's device. Leave the video pipeline untouched.
        if (isCameraOpened()) {
            pendingMicChange = true;
            recordButton.setEnabled(false);
            showStatus("Режим микрофона сохранён. Для видео переподключите USB.");
            addTrace("Mic toggled; pending until next USB connection");
        } else {
            pendingMicChange = false;
            showStatus(micEnabled ? "Микрофон включён" : "Микрофон выключен");
        }
    }

    private void registerOwnUsbReceiver() {
        if (usbReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(USB_ACTION);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbPermissionReceiver, filter);
        usbReceiverRegistered = true;
    }

    private void retryUsb(boolean userAction) {
        updateUsbDetails();
        if (isCameraOpened()) {
            showStatus("Видеопоток открыт. Если экран чёрный — переподключите кабель.");
            return;
        }
        UsbDevice device = getDefaultCamera();
        if (device == null) {
            showStatus("Не найден эндоскоп 349C:0411 • проверьте USB OTG");
            return;
        }
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (manager == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            showStatus("Нужно разрешение на камеру");
            if (userAction) requestAppPermissions();
            return;
        }
        if (manager.hasPermission(device)) {
            showStatus("USB разрешён — запрашиваем видеопоток...");
            addTrace("USB granted; AUSBC connect requested");
            requestPermission(device);
        } else if (!usbPermissionPending) {
            try {
                usbPermissionPending = true;
                Intent intent = new Intent(USB_ACTION).setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pi = PendingIntent.getBroadcast(this, 44, intent, flags);
                manager.requestPermission(device, pi);
                showStatus("Разрешите доступ к эндоскопу в системном окне");
                addTrace("Requested USB permission via mutable PendingIntent");
            } catch (Exception e) {
                usbPermissionPending = false;
                addTrace("USB permission request failed: " + e);
                showStatus("Ошибка разрешения USB: " + e.getMessage());
            }
        }
    }

    private void updateUsbDetails() {
        if (usbDetails == null) return;
        UsbDevice device = getDefaultCamera();
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (device == null) {
            usbDetails.setText("USB: камера 349C:0411 не найдена • нажмите для диагностики");
        } else {
            boolean permitted = manager != null && manager.hasPermission(device);
            usbDetails.setText("USB 349C:0411 • " +
                (permitted ? "OK" : "нет доступа") + " • " +
                (isCameraOpened() ? "OPEN" : "WAIT") +
                " • FPS " + String.format(Locale.US, "%.1f", cameraFps) +
                "/" + String.format(Locale.US, "%.1f", screenFps) +
                " • журнал ↗");
            if (isCameraOpened() && livePreview != null && livePreview.getFrames() > 2
                    && livePreview.getLastFrameAgeMs() < 2500
                    && !recording && !pendingMicChange) recordButton.setEnabled(true);
        }
    }

    private void addTrace(String message) {
        Log.i(TAG, message);
        trace.append(SystemClock.elapsedRealtime()).append(": ")
                .append(message).append('\n');
        if (trace.length() > 7000) trace.delete(0, trace.length() - 6000);
    }

    private void showDiagnostics() {
        updateUsbDetails();
        String report = "SimpleUSBCam " + Build.VERSION.RELEASE +
                " / API " + Build.VERSION.SDK_INT +
                "\nUSB: " + (usbDetails == null ? "-" : usbDetails.getText()) +
                "\nCamera: " + (isCameraOpened() ? "OPEN" : "WAIT") +
                "\nMIC: " + micEnabled +
                "\nMode: " + getVideoModeLabel() +
                "\nFrames: " + (livePreview == null ? "-" : livePreview.getFrames()) +
                "\nDisplayed: " + (livePreview == null ? "-" : livePreview.getDisplayedFrames()) +
                "\nFPS USB / screen: " + cameraFps + " / " + screenFps +
                "\nPreview: " + (livePreview == null ? "-" :
                        (livePreview.isSoftwareEnabled() ? "software" : "native")) +
                "\nLast frame age (ms): " + (livePreview == null ? "-" : livePreview.getLastFrameAgeMs()) +
                "\n\nEvents:\n" + trace;
        new AlertDialog.Builder(this)
                .setTitle("Диагностика USB")
                .setMessage(report)
                .setPositiveButton("Копировать", (dialog, which) -> {
                    android.content.ClipboardManager clip =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    if (clip != null) {
                        clip.setPrimaryClip(android.content.ClipData.newPlainText(
                                "SimpleUSBCam diagnostics", report));
                        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Закрыть", null).show();
    }

    private void calculateFps() {
        if (livePreview == null) return;
        long now = SystemClock.elapsedRealtime();
        if (fpsLastAt > 0 && now - fpsLastAt >= 400) {
            int frames = livePreview.getFrames();
            int displayed = livePreview.getDisplayedFrames();
            float seconds = (now - fpsLastAt) / 1000f;
            cameraFps = Math.max(0, (frames - fpsPreviousFrames) / seconds);
            screenFps = livePreview.isSoftwareEnabled()
                    ? Math.max(0, (displayed - fpsPreviousDisplayed) / seconds)
                    : -1f;
            fpsPreviousFrames = frames;
            fpsPreviousDisplayed = displayed;
        } else if (fpsLastAt == 0) {
            fpsPreviousFrames = livePreview.getFrames();
            fpsPreviousDisplayed = livePreview.getDisplayedFrames();
        }
        fpsLastAt = now;
    }

    private void updatePreviewModeButton() {
        if (previewModeButton != null && livePreview != null) {
            previewModeButton.setText(livePreview.isSoftwareEnabled() ? "ПРОГ" : "АПП");
        }
    }

    private void togglePreviewMode() {
        if (livePreview == null) return;
        boolean next = !livePreview.isSoftwareEnabled();
        livePreview.setSoftwareEnabled(next);
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("software_preview", next).apply();
        updatePreviewModeButton();
        addTrace("Preview mode changed to " + (next ? "software" : "native"));
        showStatus(next ? "Программный предпросмотр (оптимизирован)" :
                "Аппаратный предпросмотр; если экран чёрный, верните ПРОГ");
    }

    private String getVideoModeLabel() {
        switch (getSharedPreferences("settings", MODE_PRIVATE).getInt("video_mode", 0)) {
            case 1: return "YUYV 640×480";
            case 2: return "MJPEG 320×240";
            case 3: return "YUYV 320×240";
            default: return "MJPEG 640×480";
        }
    }

    private void updateVideoModeButton() {
        if (videoModeButton != null) {
            videoModeButton.setText(getSharedPreferences("settings", MODE_PRIVATE)
                    .getInt("video_mode", 0) % 2 == 0 ? "MJPEG" : "YUYV");
        }
    }

    private void chooseVideoMode() {
        final String[] options = {"MJPEG 640×480", "YUYV 640×480",
                "MJPEG 320×240", "YUYV 320×240"};
        int selected = getSharedPreferences("settings", MODE_PRIVATE).getInt("video_mode", 0);
        new AlertDialog.Builder(this)
                .setTitle("Формат USB-видео")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                            .putInt("video_mode", which).apply();
                    updateVideoModeButton();
                    addTrace("Selected video format: " + options[which]);
                    showStatus("Для смены формата отключите и подключите эндоскоп снова.");
                    dialog.dismiss();
                })
                .setNegativeButton("Отмена", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        updateUsbDetails();
        if (meter != null && micEnabled && !recording &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED)
            meter.startIdle();
    }

    @Override protected void onPause() {
        foreground = false;
        if (recording) stopVideo();
        if (meter != null) meter.stopAll();
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (meter != null) meter.stopAll();
        if (livePreview != null) livePreview.close();
        if (usbReceiverRegistered) {
            try { unregisterReceiver(usbPermissionReceiver); }
            catch (IllegalArgumentException ignored) { }
            usbReceiverRegistered = false;
        }
        super.onDestroy();
    }

    /**
     * AUSBC owns a native UVC camera and its status endpoint. Its UVC library
     * already exposes setButtonCallback, but the higher-level CameraUVC class
     * does not expose that method. Reflection only accesses our third-party
     * library's private camera field (not Android's hidden framework APIs).
     * A strong reference to the callback is retained for the camera lifetime.
     */
    private void attachUvcButton(MultiCameraClient.ICamera camera) {
        try {
            java.lang.reflect.Field f = camera.getClass()
                    .getDeclaredField("mUvcCamera");
            f.setAccessible(true);
            Object nativeCamera = f.get(camera);
            if (nativeCamera == null) return;
            Class<?> cbType = Class.forName("com.jiangdg.uvc.IButtonCallback");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(
                    cbType.getClassLoader(), new Class<?>[] { cbType },
                    (proxy, method, args) -> {
                        if ("onButton".equals(method.getName())
                                && args != null && args.length == 2
                                && ((Integer) args[1]) != 0) {
                            runOnUiThread(() -> {
                                long now = SystemClock.elapsedRealtime();
                                if (now - lastCableButtonAt > 450) {
                                    lastCableButtonAt = now;
                                    takePhoto();
                                }
                            });
                        }
                        return null;
                    });
            java.lang.reflect.Method setCallback = nativeCamera.getClass()
                    .getMethod("setButtonCallback", cbType);
            setCallback.invoke(nativeCamera, cb);
            uvcButtonCallback = cb;
        } catch (ReflectiveOperationException | SecurityException ex) {
            android.util.Log.w("SimpleUSBCam",
                    "UVC button callback unavailable", ex);
        }
    }

    // Fallback for endoscopes that dispatch Android camera key events.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                event.getRepeatCount() == 0 &&
                (event.getKeyCode() == KeyEvent.KEYCODE_CAMERA ||
                 event.getKeyCode() == KeyEvent.KEYCODE_FOCUS)) {
            takePhoto();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showStatus(String message) {
        if (status != null) status.setText(message);
    }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(WHITE);
        b.setAllCaps(false);
        b.setBackgroundTintList(ColorStateList.valueOf(color));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void addButton(LinearLayout row, Button button, float weight) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(48), weight);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, params);
    }

    private TextView text(String value, int sp, int color) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(sp);
        result.setTextColor(color);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private int dp(float size) {
        return (int) (getResources().getDisplayMetrics().density * size + .5f);
    }
}
