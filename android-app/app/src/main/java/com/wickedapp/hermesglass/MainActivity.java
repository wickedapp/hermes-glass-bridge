package com.wickedapp.hermesglass;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String TAG = "HermesGlassLauncher";
    private static final String VERSION = "v-aiui-clean-font12-2029";
    private static final String ASSET_AIX = "rokid-dev-console.aix";
    private static final String OUT_AIX = "rokid-dev-console.aix";
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildStatusUi();
        status("Rokid Dev Console " + VERSION + "\nPreparing AIX…");
        ensurePermissionThenLaunch();
    }

    private void buildStatusUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.rgb(5, 8, 6));
        status = new TextView(this);
        status.setTextColor(Color.rgb(234, 255, 239));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        setContentView(root);
    }

    private void status(String text) {
        Log.i(TAG, text.replace('\n', ' '));
        if (status != null) status.setText(text);
    }

    private void ensurePermissionThenLaunch() {
        if (android.os.Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            return;
        }
        copyAndLaunch();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        copyAndLaunch();
    }

    private void copyAndLaunch() {
        try {
            File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), OUT_AIX);
            copyAssetToFile(ASSET_AIX, out);
            status("Launching Rokid Dev Console\n" + VERSION + "\n" + out.getAbsolutePath());
            launchJsai(out.getAbsolutePath());
        } catch (Exception publicErr) {
            Log.e(TAG, "public Download copy failed", publicErr);
            try {
                File out = new File(getExternalFilesDir(null), OUT_AIX);
                copyAssetToFile(ASSET_AIX, out);
                status("Launching fallback path\n" + out.getAbsolutePath());
                launchJsai(out.getAbsolutePath());
            } catch (Exception fallbackErr) {
                Log.e(TAG, "fallback launch failed", fallbackErr);
                status("Launch failed\n" + fallbackErr.getClass().getSimpleName() + ": " + fallbackErr.getMessage());
            }
        }
    }

    private void copyAssetToFile(String assetName, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = getAssets().open(assetName); OutputStream os = new FileOutputStream(out, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            os.flush();
        }
        out.setReadable(true, false);
    }

    private void launchJsai(String aixPath) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
            "com.rokid.os.sprite.assistserver",
            "com.rokid.os.sprite.jsai.JsaiActivity"
        ));
        intent.putExtra("miniprogram_path", aixPath);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }
}
