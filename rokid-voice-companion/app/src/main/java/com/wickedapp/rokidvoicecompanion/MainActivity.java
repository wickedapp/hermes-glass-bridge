package com.wickedapp.rokidvoicecompanion;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rokid.cxr.Caps;
import com.rokid.cxr.link.CXRLink;
import com.rokid.cxr.link.callbacks.ICXRLinkCbk;
import com.rokid.cxr.link.callbacks.ICustomCmdCbk;
import com.rokid.cxr.link.utils.CxrDefs;
import com.rokid.cxr.link.utils.GlassInfo;
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult;
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String TAG = "RokidVoiceCompanion";
    private static final String MSG_DICTATE_START = "tg.dictate.start";
    private static final String MSG_DICTATE_CANCEL = "tg.dictate.cancel";
    private static final String MSG_ASR = "tg.asr";
    private static final String GLASSES_APP_PACKAGE = "com.wickedapp.rokidtg";
    private static final String GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp";
    private static final String AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity";
    private static final String AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION";
    private static final String MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE";
    private static final String AUTH_TOKEN_EXTRA = "auth_token";
    private static final String AUTH_PACKAGE_EXTRA = "auth_package";
    private static final int REQ_PERMS = 710;
    private static final int REQ_ROKID_AUTH = 711;
    private static final int REQ_SPEECH_ACTIVITY = 712;

    private TextView status;
    private TextView transcript;
    private Button authButton;
    private Button connectButton;
    private SpeechRecognizer recognizer;
    private String activeSessionId = null;
    private String pendingSpeechLang = "zh-CN";
    private String authToken = null;
    private CXRLink cxrLink = null;
    private boolean cxrlConnected = false;
    private boolean glassBtConnected = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNeededPermissions();
        setupCxrlLink();
        requestRokidAuthorization();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(0xff000000);

        TextView title = new TextView(this);
        title.setText("Rokid Voice Companion\nCXR-L ASR via Hi Rokid");
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

        connectButton = new Button(this);
        connectButton.setText("Connect CXR-L");
        connectButton.setOnClickListener(v -> connectCxrl());
        root.addView(connectButton);

        transcript = new TextView(this);
        transcript.setTextColor(0xff40ff5e);
        transcript.setTextSize(20);
        transcript.setPadding(0, 24, 0, 0);
        root.addView(transcript);

        setContentView(root);
        setStatus("Authorize Hi Rokid, then wait for tg.dictate.start…");
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
        };
        for (String p : perms) {
            if (Build.VERSION.SDK_INT < 31 &&
                (p.equals(Manifest.permission.BLUETOOTH_CONNECT) || p.equals(Manifest.permission.BLUETOOTH_SCAN))) {
                continue;
            }
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    private void setupCxrlLink() {
        CXRLink link = new CXRLink(getApplicationContext());
        boolean configured = link.configCXRSession(
            new CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, GLASSES_APP_PACKAGE)
        );
        Log.i(TAG, "configCXRSession CUSTOMAPP " + GLASSES_APP_PACKAGE + " -> " + configured);
        link.setCXRLinkCbk(new ICXRLinkCbk() {
            @Override public void onCXRLConnected(boolean connected) {
                cxrlConnected = connected;
                Log.i(TAG, "CXR-L service connected=" + connected);
                runOnUiThread(MainActivity.this::updateReadyStatus);
            }
            @Override public void onGlassBtConnected(boolean connected) {
                glassBtConnected = connected;
                Log.i(TAG, "Hi Rokid glasses BT connected=" + connected);
                runOnUiThread(MainActivity.this::updateReadyStatus);
            }
            @Override public void onGlassDeviceInfo(GlassInfo info) {
                Log.i(TAG, "GlassInfo=" + info);
            }
            @Override public void onGlassWearingStatus(boolean wearing) { }
            @Override public void onGlassAiAssistStart() { Log.i(TAG, "Hi Rokid assistant started"); }
            @Override public void onGlassAiAssistStop() { Log.i(TAG, "Hi Rokid assistant stopped"); }
            @Override public void onGlassAiInterrupt(boolean interrupted) { }
        });
        link.setCXRCustomCmdCbk(new ICustomCmdCbk() {
            @Override public void onCustomCmdResult(String key, byte[] payload) {
                Log.i(TAG, "onCustomCmdResult key=" + key + " bytes=" + (payload == null ? -1 : payload.length));
                Caps args = null;
                if (payload != null) {
                    try { args = Caps.fromBytes(payload); }
                    catch (Exception e) { Log.w(TAG, "Caps.fromBytes failed: " + e.getMessage(), e); }
                }
                final Caps finalArgs = args;
                runOnUiThread(() -> handleCxrCommand(key, finalArgs));
            }
        });
        cxrLink = link;
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
        if (requestCode == REQ_SPEECH_ACTIVITY) {
            String sessionId = activeSessionId;
            if (sessionId == null || sessionId.isEmpty()) return;
            if (resultCode == RESULT_OK && data != null) {
                String text = bestText(data.getExtras());
                transcript.setText(text);
                if (text.isEmpty()) sendAsr(sessionId, "error", "no_match");
                else sendAsr(sessionId, "final", text);
                sendAsr(sessionId, "end", "");
                setStatus("Final sent via speech activity. Waiting for next request…");
            } else {
                sendAsr(sessionId, "error", "speech_activity_cancelled");
                setStatus("Speech activity cancelled");
            }
            activeSessionId = null;
            return;
        }
        if (requestCode != REQ_ROKID_AUTH) return;
        AuthResult result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data);
        if (result instanceof AuthResult.AuthSuccess) {
            authToken = ((AuthResult.AuthSuccess) result).getToken();
            Log.i(TAG, "Hi Rokid authorization token received len=" + (authToken == null ? 0 : authToken.length()));
            setStatus("Hi Rokid authorized. Connecting CXR-L…");
            connectCxrl();
        } else if (result instanceof AuthResult.AuthCancel) {
            setStatus("Hi Rokid authorization cancelled");
        } else {
            setStatus("Hi Rokid authorization failed");
        }
    }

    private void connectCxrl() {
        if (authToken == null || authToken.isEmpty()) {
            setStatus("Missing Hi Rokid authorization token; press Authorize");
            return;
        }
        if (cxrLink == null) setupCxrlLink();
        boolean bound = bindGlobalHiRokidService(cxrLink, authToken);
        Log.i(TAG, "bindGlobalHiRokidService -> " + bound);
        if (bound) setStatus("Binding to Hi Rokid CXR-L service…");
        else setStatus("CXR-L service bind failed; open/force-close Hi Rokid then retry");
    }

    private boolean bindGlobalHiRokidService(CXRLink link, String token) {
        try {
            ServiceConnection connection = findServiceConnection(link);
            Intent intent = new Intent(MEDIA_SERVICE_ACTION)
                .setPackage(GLOBAL_AI_APP_PACKAGE)
                .putExtra(AUTH_TOKEN_EXTRA, token)
                .putExtra(AUTH_PACKAGE_EXTRA, getApplicationContext().getPackageName());
            return getApplicationContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w(TAG, "reflection bind failed", e);
            return false;
        }
    }

    private ServiceConnection findServiceConnection(CXRLink link) throws Exception {
        Class<?> type = link.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (ServiceConnection.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (ServiceConnection) field.get(link);
                }
            }
            type = type.getSuperclass();
        }
        throw new IllegalStateException("CXR-L ServiceConnection field not found");
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

    private void updateReadyStatus() {
        setStatus("CXR-L=" + cxrlConnected + " glassesBT=" + glassBtConnected + ". Waiting for " + MSG_DICTATE_START + "…");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) setStatus("Permissions updated. Authorize Hi Rokid…");
    }

    private void handleCxrCommand(String name, Caps args) {
        if (MSG_DICTATE_START.equals(name)) {
            String sessionId = stringAt(args, 0);
            String chatId = stringAt(args, 1);
            String lang = stringAt(args, 2);
            startDictation(sessionId, chatId, lang.isEmpty() ? "zh-CN" : lang);
        } else if (MSG_DICTATE_CANCEL.equals(name)) {
            String sessionId = stringAt(args, 0);
            if (sessionId.equals(activeSessionId)) cancelDictation("cancelled");
        } else {
            Log.i(TAG, "Ignoring custom cmd key=" + name);
        }
    }

    private void startDictation(String sessionId, String chatId, String lang) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendAsr(sessionId, "error", "permission");
            requestNeededPermissions();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("SpeechRecognizer service unavailable; opening speech activity…");
            activeSessionId = sessionId;
            pendingSpeechLang = lang;
            sendAsr(sessionId, "ready", "");
            launchSpeechActivity(sessionId, lang);
            return;
        }
        cancelDictation("new-session");
        activeSessionId = sessionId;
        pendingSpeechLang = lang;
        setStatus("Listening for chat " + chatId + " lang=" + lang);
        transcript.setText("");
        sendAsr(sessionId, "ready", "");

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { setStatus("Ready for speech…"); }
            @Override public void onBeginningOfSpeech() { setStatus("Hearing speech…"); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { setStatus("Transcribing…"); }
            @Override public void onError(int error) {
                String code = "speech_error_" + error;
                if (error == 10) {
                    setStatus("Recognizer bind failed; opening speech activity…");
                    Log.w(TAG, "SpeechRecognizer bind/server error; fallback to RecognizerIntent activity");
                    cleanupRecognizer();
                    launchSpeechActivity(sessionId, pendingSpeechLang);
                    return;
                }
                setStatus("ASR error " + code);
                sendAsr(sessionId, "error", code);
                cleanupRecognizer();
                activeSessionId = null;
            }
            @Override public void onResults(Bundle results) {
                String text = bestText(results);
                transcript.setText(text);
                if (text.isEmpty()) sendAsr(sessionId, "error", "no_match");
                else sendAsr(sessionId, "final", text);
                sendAsr(sessionId, "end", "");
                cleanupRecognizer();
                activeSessionId = null;
                setStatus("Final sent. Waiting for next request…");
            }
            @Override public void onPartialResults(Bundle partialResults) {
                String text = bestText(partialResults);
                if (!text.isEmpty()) {
                    transcript.setText(text);
                    sendAsr(sessionId, "partial", text);
                }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizer.startListening(intent);
    }

    private void launchSpeechActivity(String sessionId, String lang) {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak Telegram reply");
            Log.i(TAG, "launch speech activity session=" + sessionId + " lang=" + lang);
            startActivityForResult(intent, REQ_SPEECH_ACTIVITY);
        } catch (Exception e) {
            Log.w(TAG, "speech activity unavailable", e);
            sendAsr(sessionId, "error", "speech_activity_unavailable");
            activeSessionId = null;
        }
    }

    private void cancelDictation(String reason) {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            cleanupRecognizer();
        }
        if (activeSessionId != null) sendAsr(activeSessionId, "error", reason);
        activeSessionId = null;
    }

    private void cleanupRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void sendAsr(String sessionId, String event, String payload) {
        CXRLink link = cxrLink;
        if (link == null) {
            Log.w(TAG, "sendAsr dropped: CXRLink missing event=" + event);
            return;
        }
        Caps caps = new Caps();
        caps.write(sessionId == null ? "" : sessionId);
        caps.write(event == null ? "" : event);
        caps.write(payload == null ? "" : payload);
        Integer result = link.sendCustomCmd(MSG_ASR, caps);
        Log.i(TAG, "send " + MSG_ASR + " event=" + event + " result=" + result + " payloadLen=" + (payload == null ? 0 : payload.length()));
    }

    private String bestText(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return "";
        for (String s : matches) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    private String stringAt(Caps args, int index) {
        try {
            if (args == null || args.size() <= index) return "";
            String s = args.at(index).getString();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private void setStatus(String text) {
        Log.i(TAG, text);
        if (status != null) status.setText(text);
    }

    @Override protected void onDestroy() {
        cleanupRecognizer();
        try { if (cxrLink != null) cxrLink.disconnect(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
