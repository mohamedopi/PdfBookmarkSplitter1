package com.example.pdfsplit;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_PICK_PDF = 1001;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    private static class Pending {
        Uri uri;
        OutputStream out;
    }

    private final Map<String, Pending> pending = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0B7A75);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true); // التطبيق يعمل بالكامل بدون إنترنت

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                try {
                    startActivityForResult(intent, REQ_PICK_PDF);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_PDF || filePathCallback == null) {
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /** يكتب الملفات في Download/اسم_المجلد عبر MediaStore (لا يحتاج أي إذن على أندرويد 10 فأعلى). */
    public class Bridge {

        @JavascriptInterface
        public String begin(String folder, String name) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver r = getContentResolver();
                Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    return "";
                }
                OutputStream out = r.openOutputStream(uri);
                if (out == null) {
                    r.delete(uri, null, null);
                    return "";
                }
                Pending p = new Pending();
                p.uri = uri;
                p.out = out;
                String id = UUID.randomUUID().toString();
                synchronized (pending) {
                    pending.put(id, p);
                }
                return id;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean append(String id, String base64) {
            Pending p;
            synchronized (pending) {
                p = pending.get(id);
            }
            if (p == null) {
                return false;
            }
            try {
                p.out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean end(String id) {
            Pending p;
            synchronized (pending) {
                p = pending.remove(id);
            }
            if (p == null) {
                return false;
            }
            try {
                p.out.flush();
                p.out.close();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(p.uri, v, null, null);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void abort(String id) {
            Pending p;
            synchronized (pending) {
                p = pending.remove(id);
            }
            if (p == null) {
                return;
            }
            try {
                p.out.close();
            } catch (Exception ignored) {
            }
            try {
                getContentResolver().delete(p.uri, null, null);
            } catch (Exception ignored) {
            }
        }
    }
}
