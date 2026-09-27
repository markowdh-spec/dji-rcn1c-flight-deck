package com.drone.rcn1cbridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class SimulatorActivity extends Activity implements Rcn1cUsbReader.Listener {
    private static final String ACTION_USB_PERMISSION = "com.drone.rcn1cbridge.SIMULATOR_USB_PERMISSION";
    private static final String LOCAL_ASSET_BASE = "https://appassets.androidplatform.net/assets/flight-zone/";
    private static final String LOCAL_ASSET_HOST = "appassets.androidplatform.net";
    private static final long RETRY_MS = 1200L;
    private static final long FRAME_PUSH_MS = 33L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Rcn1cUsbReader.Frame latestFrame;
    private volatile boolean pageReady;
    private volatile boolean closing;
    private volatile int readerDeviceId = -1;
    private boolean frameStreamConnected;
    private volatile boolean screenOnRequested;
    private long lastPushedPacketCount = -1L;

    private WebView webView;
    private Rcn1cUsbReader reader;

    private final Runnable retryReader = new Runnable() {
        @Override
        public void run() {
            if (!closing) startReaderIfPossible();
        }
    };

    private final Runnable framePump = new Runnable() {
        @Override
        public void run() {
            if (closing) return;
            pushLatestFrame();
            ui.postDelayed(this, FRAME_PUSH_MS);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                postStatus(granted ? "Permesso USB ricevuto" : "Permesso USB negato");
                if (granted) startReaderIfPossible();
                else scheduleReaderRetry();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice detached = detachedDevice(intent);
                if (detached == null || readerDeviceId == -1
                        || detached.getDeviceId() != readerDeviceId) return;
                latestFrame = null;
                frameStreamConnected = false;
                ui.post(() -> setScreenOn(false));
                if (reader != null) reader.stop();
                postStatus("RC scollegato · collega di nuovo il radiocomando");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        reader = new Rcn1cUsbReader(
                (UsbManager) getSystemService(USB_SERVICE), this);
        ui.post(framePump);
        startReaderIfPossible();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        ui.removeCallbacks(retryReader);
        ui.removeCallbacks(framePump);
        latestFrame = null;
        setScreenOn(false);
        if (reader != null) reader.stopAndWait(500L);
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.setBackgroundColor(0xFF05080B);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isSimulatorDashboard(uri)) {
                    finish();
                    return true;
                }
                return !isLocalAsset(uri);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                               WebResourceRequest request) {
                return openLocalAsset(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                postStatus("Simulatore pronto · attendo RC-N1C");
            }
        });
        webView.loadDataWithBaseURL(
                LOCAL_ASSET_BASE + "index.html",
                readAssetText("flight-zone/index.html"),
                "text/html",
                "UTF-8",
                null);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void startReaderIfPossible() {
        if (closing || reader == null || reader.isRunning()) return;
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice device = Rcn1cUsbReader.findDji(manager);
        if (device == null) {
            postStatus("Collega l'RC-N1C via OTG");
            scheduleReaderRetry();
            return;
        }
        if (!manager.hasPermission(device)) {
            requestUsbPermission(manager, device);
            postStatus("Conferma il permesso USB per l'RC");
            return;
        }
        readerDeviceId = device.getDeviceId();
        latestFrame = null;
        lastPushedPacketCount = -1L;
        frameStreamConnected = false;
        if (!reader.start(device)) {
            postStatus("Impossibile avviare il lettore RC");
            readerDeviceId = -1;
            scheduleReaderRetry();
            return;
        }
    }

    private void requestUsbPermission(UsbManager manager, UsbDevice device) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        manager.requestPermission(device, pendingIntent);
    }

    private void pushLatestFrame() {
        Rcn1cUsbReader.Frame frame = latestFrame;
        if (!pageReady || webView == null || closing) return;
        if (frame == null || reader == null || !reader.isRunning()) {
            latestFrame = null;
            lastPushedPacketCount = -1L;
            if (frameStreamConnected) {
                frameStreamConnected = false;
                webView.evaluateJavascript(
                        "window.setRcn1cStatus('RC scollegato',false);", null);
            }
            return;
        }
        if (frame.packetCount == lastPushedPacketCount) return;
        lastPushedPacketCount = frame.packetCount;
        frameStreamConnected = true;
        String script = String.format(Locale.US,
                "window.setRcn1cFrame(%d,%d,%d,%d,%d,%d,%.1f,true,%d);",
                frame.lx, frame.ly, frame.rx, frame.ry,
                frame.buttonMask, frame.mode, frame.packetsPerSecond, frame.rawCamera);
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onStatus(String message) {
        postStatus(message);
    }

    @Override
    public void onFrame(Rcn1cUsbReader.Frame frame) {
        latestFrame = frame;
        if (!screenOnRequested) ui.post(() -> setScreenOn(true));
    }

    @Override
    public void onStopped(String reason) {
        if (!closing) {
            latestFrame = null;
            lastPushedPacketCount = -1L;
            frameStreamConnected = false;
            readerDeviceId = -1;
            ui.post(() -> setScreenOn(false));
            postStatus("Lettore fermo · " + reason);
            scheduleReaderRetry();
        }
    }

    private void postStatus(String message) {
        ui.post(() -> {
            if (closing) return;
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.setRcn1cStatus(" + JSONObject.quote(message) + "," +
                                (reader != null && reader.isRunning()) + ");", null);
            }
        });
    }

    private void scheduleReaderRetry() {
        ui.removeCallbacks(retryReader);
        if (!closing) ui.postDelayed(retryReader, RETRY_MS);
    }

    private void setScreenOn(boolean keepOn) {
        if (screenOnRequested == keepOn) return;
        screenOnRequested = keepOn;
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @SuppressWarnings("deprecation")
    private UsbDevice detachedDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private boolean isLocalAsset(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme())
                || !LOCAL_ASSET_HOST.equals(uri.getHost())) return false;
        String path = uri.getPath();
        return path != null
                && (path.startsWith("/assets/flight-zone/") || path.startsWith("/assets/fpv-sim/"))
                && !path.contains("..");
    }

    private boolean isSimulatorDashboard(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme())
                || !LOCAL_ASSET_HOST.equals(uri.getHost())) return false;
        String path = uri.getPath();
        return path == null || path.isEmpty() || "/".equals(path);
    }

    private WebResourceResponse openLocalAsset(Uri uri) {
        if (!isLocalAsset(uri)) return null;
        String assetPath = uri.getPath().substring("/assets/".length());
        try {
            String mime = mimeType(assetPath);
            return new WebResourceResponse(
                    mime,
                    isTextMime(mime) ? "UTF-8" : null,
                    getAssets().open(assetPath));
        } catch (IOException ignored) {
            return null;
        }
    }

    private String readAssetText(String assetPath) {
        try (InputStream input = getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Asset simulatore non leggibile", e);
        }
    }

    private static String mimeType(String assetPath) {
        if (assetPath.endsWith(".js")) return "application/javascript";
        if (assetPath.endsWith(".css")) return "text/css";
        if (assetPath.endsWith(".html")) return "text/html";
        if (assetPath.endsWith(".png")) return "image/png";
        if (assetPath.endsWith(".jpg") || assetPath.endsWith(".jpeg")) return "image/jpeg";
        if (assetPath.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private static boolean isTextMime(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.contains("javascript"));
    }

}
