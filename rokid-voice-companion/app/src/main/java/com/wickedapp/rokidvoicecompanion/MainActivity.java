package com.wickedapp.rokidvoicecompanion;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rokid.sprite.aiapp.externalapp.auth.AuthResult;
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String TAG = "RokidVoiceCompanion";
    private static final String GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp";
    private static final String AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity";
    private static final String AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION";
    private static final int REQ_PERMS = 710;
    private static final int REQ_ROKID_AUTH = 711;

    private TextView status;
    private Button authButton;
    private Button backgroundButton;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNeededPermissions();
        String savedToken = getSharedPreferences(VoiceCompanionService.PREFS, MODE_PRIVATE)
            .getString(VoiceCompanionService.PREF_AUTH_TOKEN, null);
        if (savedToken != null && !savedToken.isEmpty()) {
            startCompanion(savedToken, true);
        } else {
            requestRokidAuthorization();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(0xff000000);

        TextView title = new TextView(this);
        title.setText("Rokid Voice Companion\nBackground CXR-L ASR\nDefault: 繁體中文");
        title.setTextColor(0xff40ff5e);
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xffffffff);
        status.setTextSize(16);
        status.setPadding(0, 24, 0, 8);
        root.addView(status);

        authButton = new Button(this);
        authButton.setText("Authorize Hi Rokid");
        authButton.setOnClickListener(v -> requestRokidAuthorization());
        root.addView(authButton);

        backgroundButton = new Button(this);
        backgroundButton.setText("Start in background");
        backgroundButton.setOnClickListener(v -> {
            String token = getSharedPreferences(VoiceCompanionService.PREFS, MODE_PRIVATE)
                .getString(VoiceCompanionService.PREF_AUTH_TOKEN, null);
            if (token == null || token.isEmpty()) requestRokidAuthorization();
            else startCompanion(token, true);
        });
        root.addView(backgroundButton);

        setContentView(root);
        setStatus("Authorizing once; then this app runs as a foreground service.");
    }

    private void requestNeededPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        String[] perms = new String[] {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        };
        for (String p : perms) {
            if (Build.VERSION.SDK_INT < 31 &&
                (p.equals(Manifest.permission.BLUETOOTH_CONNECT) || p.equals(Manifest.permission.BLUETOOTH_SCAN))) continue;
            if (Build.VERSION.SDK_INT < 33 && p.equals(Manifest.permission.POST_NOTIFICATIONS)) continue;
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    private void requestRokidAuthorization() {
        if (!isGlobalHiRokidInstalled()) {
            setStatus("Hi Rokid/global AI app not installed on this phone");
            return;
        }
        try {
            Intent intent = new Intent().setComponent(new ComponentName(GLOBAL_AI_APP_PACKAGE, AUTH_ACTIVITY_CLASS));
            startActivityForResult(intent, REQ_ROKID_AUTH);
            setStatus("Hi Rokid authorization opened…");
        } catch (Exception first) {
            try {
                Intent fallback = new Intent(AUTH_ACTION).setPackage(GLOBAL_AI_APP_PACKAGE);
                startActivityForResult(fallback, REQ_ROKID_AUTH);
                setStatus("Hi Rokid authorization opened via action…");
            } catch (Exception second) {
                Log.w(TAG, "open Hi Rokid authorization failed", second);
                setStatus("Failed to open Hi Rokid authorization: " + second.getMessage());
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ROKID_AUTH) return;
        AuthResult result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data);
        if (result instanceof AuthResult.AuthSuccess) {
            String token = ((AuthResult.AuthSuccess) result).getToken();
            Log.i(TAG, "Hi Rokid authorization token received len=" + (token == null ? 0 : token.length()));
            startCompanion(token, true);
        } else if (result instanceof AuthResult.AuthCancel) {
            setStatus("Hi Rokid authorization cancelled");
        } else {
            setStatus("Hi Rokid authorization failed");
        }
    }

    private void startCompanion(String token, boolean moveToBackground) {
        if (token == null || token.isEmpty()) {
            setStatus("Missing Hi Rokid authorization token; press Authorize");
            return;
        }
        getSharedPreferences(VoiceCompanionService.PREFS, MODE_PRIVATE)
            .edit().putString(VoiceCompanionService.PREF_AUTH_TOKEN, token).apply();
        VoiceCompanionService.start(this, token);
        setStatus("Background service started. You can leave this app; default ASR language is 繁體中文 (zh-TW).");
        if (moveToBackground) status.postDelayed(() -> moveTaskToBack(true), 700);
    }

    private boolean isGlobalHiRokidInstalled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageManager().getPackageInfo(GLOBAL_AI_APP_PACKAGE, PackageManager.PackageInfoFlags.of(0));
            } else {
                getPackageManager().getPackageInfo(GLOBAL_AI_APP_PACKAGE, 0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) setStatus("Permissions updated. Starting/authorizing companion…");
    }

    private void setStatus(String text) {
        Log.i(TAG, text);
        if (status != null) status.setText(text);
    }
}
