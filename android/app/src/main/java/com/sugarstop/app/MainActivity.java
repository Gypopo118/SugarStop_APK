package com.sugarstop.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * SugarStop — fullscreen WebView over the bundled PWA
 * (android/app/src/main/assets/www, synced from /public by CI).
 *
 * The bundle is loaded via file:// (asset paths are relativized at build
 * time), so there is no live in-page camera preview. Instead the PWA's
 * shutter button calls SugarStopNative.takePhoto() which opens the system
 * camera directly (one tap, no chooser); the captured photo is downscaled
 * and handed back to the page as a data URL.
 *
 * No backend URL is needed: when /api/analyze is unreachable (file://),
 * the PWA falls back to direct provider calls via SugarStopDirect.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_CODE_FILE_CHOOSER = 1001;
    private static final int REQUEST_CODE_PERMISSIONS = 1002;
    private static final int REQUEST_CODE_NATIVE_CAMERA = 1003;
    private static final String START_PAGE = "file:///android_asset/www/index.html";
    private static final int NATIVE_PHOTO_MAX_SIZE = 1024;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;
    private Uri nativeCameraUri;
    private boolean pendingNativeCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        configureWebView();
        requestAppPermissions();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_PAGE);
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // The bundled pages live under file://, AI endpoints are https:// —
        // allow the bundled pages to call them directly.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua + " SugarStopAPK/1.0.3");

        webView.addJavascriptInterface(new NativeBridge(), "SugarStopNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            private boolean handleUrl(Uri uri) {
                if (uri == null) return true;
                String scheme = uri.getScheme();
                if (scheme == null) return true;
                // Everything local stays inside the WebView.
                if (scheme.equals("file") || scheme.equals("about") || scheme.equals("data")) {
                    return false;
                }
                // External links (e.g. platform.deepseek.com) open in the browser.
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Нет приложения для открытия ссылки", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Grant camera/mic if a page ever requests getUserMedia.
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                openImageChooser();
                return true;
            }
        });
    }

    /** Bridge called from the PWA: one tap on the shutter opens the system camera. */
    private class NativeBridge {
        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(() -> launchNativeCamera());
        }
    }

    private void launchNativeCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingNativeCamera = true;
            requestPermissions(
                    new String[]{android.Manifest.permission.CAMERA},
                    REQUEST_CODE_PERMISSIONS);
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "SugarStop capture");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            nativeCameraUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (nativeCameraUri != null) {
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, nativeCameraUri);
            }
            startActivityForResult(cameraIntent, REQUEST_CODE_NATIVE_CAMERA);
        } catch (ActivityNotFoundException e) {
            nativeCameraUri = null;
            Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS && pendingNativeCamera) {
            pendingNativeCamera = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchNativeCamera();
            } else {
                Toast.makeText(this, "Без доступа к камере фото не сделать", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Downscale the captured photo and hand it to the page as a data URL. */
    private void deliverNativePhoto(final Uri uri) {
        new Thread(() -> {
            final String dataUrl = buildPhotoDataUrl(uri);
            runOnUiThread(() -> {
                if (webView == null || dataUrl == null) return;
                webView.evaluateJavascript(
                        "window.__sugarstopNativePhoto('" + dataUrl + "')", null);
            });
        }).start();
    }

    private String buildPhotoDataUrl(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            int sampleSize = 1;
            while (maxSide / (sampleSize * 2) >= NATIVE_PHOTO_MAX_SIZE) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            Bitmap bitmap;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bitmap == null) return null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            bitmap.recycle();
            String base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + base64;
        } catch (Exception e) {
            return null;
        }
    }

    private void requestAppPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        String[] perms = {
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
        };
        boolean need = false;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            requestPermissions(perms, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void openImageChooser() {
        try {
            // Camera capture target via MediaStore (no FileProvider needed).
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "SugarStop capture");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraPhotoUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (cameraPhotoUri != null) {
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
            }

            Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
            galleryIntent.setType("image/*");

            Intent chooser = Intent.createChooser(galleryIntent, "Выберите фото");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            startActivityForResult(chooser, REQUEST_CODE_FILE_CHOOSER);
        } catch (ActivityNotFoundException e) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_NATIVE_CAMERA) {
            if (resultCode == Activity.RESULT_OK && nativeCameraUri != null) {
                deliverNativePhoto(nativeCameraUri);
            }
            nativeCameraUri = null;
            return;
        }
        if (requestCode != REQUEST_CODE_FILE_CHOOSER || filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (cameraPhotoUri != null) {
                // Photo was captured to our MediaStore URI.
                results = new Uri[]{cameraPhotoUri};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraPhotoUri = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
