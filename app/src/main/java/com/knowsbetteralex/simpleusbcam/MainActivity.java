package com.knowsbetteralex.simpleusbcam;

import android.Manifest;
import android.content.pm.PackageManager;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private MicLevelMonitor meter;
    private boolean foreground = false;
    private boolean recording = false;
    private boolean micEnabled = true;
    private int rotation = 0;
    private long startedAt;

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
        root.addView(header);

        cameraContainer = new FrameLayout(this);
        cameraContainer.setBackgroundColor(Color.BLACK);
        cameraView = new AspectRatioTextureView(this);
        // CameraActivity will attach this view when it initializes.
        root.addView(cameraContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = text("Подключите USB-эндоскоп", 13, 0xffa3b2bc);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        root.addView(status, slp);

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
            if (isCameraOpened()) {
                setRotateType(new RotateType[] {
                    RotateType.ANGLE_0, RotateType.ANGLE_90,
                    RotateType.ANGLE_180, RotateType.ANGLE_270
                }[rotation]);
            }
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
        return new CameraRequest.Builder()
                .setPreviewWidth(640)
                .setPreviewHeight(480)
                .setRenderMode(CameraRequest.RenderMode.OPENGL)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
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
        requestAppPermissions();
    }

    private void requestAppPermissions() {
        java.util.ArrayList<String> required = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            required.add(Manifest.permission.RECORD_AUDIO);
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
        } else if (getCurrentCamera() != null && !isCameraOpened()) {
            openCamera(cameraView);
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
                    setEncodeDataCallBack((type, data, offset, size, pts) -> {
                        if (type == IEncodeDataCallBack.DataType.AAC && recording)
                            meter.offerAac(data, offset, size);
                    });
                    setRotateType(new RotateType[] {
                        RotateType.ANGLE_0, RotateType.ANGLE_90,
                        RotateType.ANGLE_180, RotateType.ANGLE_270
                    }[rotation]);
                    showStatus("Эндоскоп подключён • 640×480");
                    break;
                case CLOSED:
                    if (recording) stopVideo();
                    showStatus("USB-камера отключена");
                    break;
                case ERROR:
                    if (recording) stopVideo();
                    showStatus("Ошибка USB: " + (message == null ? "неизвестно" : message));
                    break;
                default: break;
            }
        });
    }

    private ICaptureCallBack captureCallback(String kind) {
        return new ICaptureCallBack() {
            @Override public void onBegin() {
                ui.post(() -> showStatus(kind + ": сохранение началось"));
            }
            @Override public void onError(String error) {
                ui.post(() -> {
                    showStatus(kind + ": " + (error == null ? "ошибка" : error));
                    if (recording && "Видео".equals(kind)) finishRecordingUi();
                });
            }
            @Override public void onComplete(String path) {
                ui.post(() -> {
                    showStatus(kind + " сохранено" +
                            (path == null ? "" : ": " + path));
                    if (recording && "Видео".equals(kind)) finishRecordingUi();
                });
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
        startedAt = SystemClock.elapsedRealtime();
        recordButton.setText("■ Стоп");
        recordButton.setBackgroundTintList(ColorStateList.valueOf(0xffa02f38));
        micButton.setEnabled(false);
        ui.removeCallbacks(timeTick);
        ui.post(timeTick);
        meter.stopIdle();
        if (micEnabled) meter.startDecoder();
        try {
            captureVideoStart(captureCallback("Видео"), null, 0L);
            showStatus(micEnabled ? "Запись видео + микрофон телефона" :
                    "Запись видео без звука");
        } catch (Exception ex) {
            showStatus("Не удалось начать запись: " + ex.getMessage());
            finishRecordingUi();
        }
    }

    private void stopVideo() {
        if (!recording) return;
        captureVideoStop();
        finishRecordingUi();
        showStatus("Сохраняем MP4...");
    }

    private void finishRecordingUi() {
        recording = false;
        ui.removeCallbacks(timeTick);
        recordButton.setText("● Запись");
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
        if (micEnabled) requestAppPermissions(); else {
            meter.stopAll();
            meterBar.setProgress(0);
        }
        if (isCameraOpened()) {
            showStatus("Перезапуск камеры для смены источника звука...");
            closeCamera();
            ui.postDelayed(() -> {
                if (foreground) openCamera(cameraView);
            }, 650);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
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
        super.onDestroy();
    }

    // Some endoscopes generate an Android key code; others send a UVC
    // still-image event on the video-control interrupt endpoint instead.
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
