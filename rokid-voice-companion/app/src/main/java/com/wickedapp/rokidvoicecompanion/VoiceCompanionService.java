package com.wickedapp.rokidvoicecompanion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.rokid.cxr.Caps;
import com.rokid.cxr.link.CXRLink;
import com.rokid.cxr.link.callbacks.ICXRLinkCbk;
import com.rokid.cxr.link.callbacks.ICustomCmdCbk;
import com.rokid.cxr.link.utils.CxrDefs;
import com.rokid.cxr.link.utils.GlassInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class VoiceCompanionService extends Service {
    private static final String TAG = "RokidVoiceCompanion";
    private static final String MSG_DICTATE_START = "tg.dictate.start";
    private static final String MSG_DICTATE_CANCEL = "tg.dictate.cancel";
    private static final String MSG_ASR = "tg.asr";
    private static final String GLASSES_APP_PACKAGE = "com.wickedapp.rokidtg";
    private static final String GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp";
    private static final String MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE";
    private static final String AUTH_TOKEN_EXTRA = "auth_token";
    private static final String AUTH_PACKAGE_EXTRA = "auth_package";
    private static final String CHANNEL_ID = "rokid_voice_companion";
    private static final int NOTIFICATION_ID = 8010;
    public static final String EXTRA_AUTH_TOKEN = "auth_token";
    public static final String PREFS = "rokid_voice_companion";
    public static final String PREF_AUTH_TOKEN = "auth_token";
    public static final String DEFAULT_ASR_LANG = "zh-TW";

    private final Handler main = new Handler(Looper.getMainLooper());
    private CXRLink cxrLink;
    private SpeechRecognizer recognizer;
    private String activeSessionId = null;
    private String pendingSpeechLang = DEFAULT_ASR_LANG;
    private String authToken = null;
    private boolean cxrlConnected = false;
    private boolean glassBtConnected = false;

    public static void start(Context context, String token) {
        Intent intent = new Intent(context, VoiceCompanionService.class);
        if (token != null && !token.isEmpty()) intent.putExtra(EXTRA_AUTH_TOKEN, token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"));
        setupCxrlLink();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String token = intent == null ? null : intent.getStringExtra(EXTRA_AUTH_TOKEN);
        if (token != null && !token.isEmpty()) {
            authToken = token;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_AUTH_TOKEN, token).apply();
        } else {
            authToken = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AUTH_TOKEN, null);
        }
        if (authToken == null || authToken.isEmpty()) {
            Log.w(TAG, "service missing Hi Rokid auth token; open app once to authorize");
            updateNotification("Needs Hi Rokid authorization");
        } else {
            connectCxrl();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Rokid Voice Companion", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rokid Voice Companion")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        Log.i(TAG, text);
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
                updateReadyStatus();
            }
            @Override public void onGlassBtConnected(boolean connected) {
                glassBtConnected = connected;
                Log.i(TAG, "Hi Rokid glasses BT connected=" + connected);
                updateReadyStatus();
            }
            @Override public void onGlassDeviceInfo(GlassInfo info) { Log.i(TAG, "GlassInfo=" + info); }
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
                main.post(() -> handleCxrCommand(key, finalArgs));
            }
        });
        cxrLink = link;
    }

    private void connectCxrl() {
        if (cxrLink == null) setupCxrlLink();
        boolean bound = bindGlobalHiRokidService(cxrLink, authToken);
        Log.i(TAG, "bindGlobalHiRokidService -> " + bound);
        updateNotification(bound ? "Binding to Hi Rokid CXR-L service…" : "CXR-L service bind failed");
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

    private void updateReadyStatus() {
        updateNotification("CXR-L=" + cxrlConnected + " glassesBT=" + glassBtConnected + ". Waiting for Dictate…");
    }

    private void handleCxrCommand(String name, Caps args) {
        if (MSG_DICTATE_START.equals(name)) {
            String sessionId = stringAt(args, 0);
            String chatId = stringAt(args, 1);
            String lang = stringAt(args, 2);
            startDictation(sessionId, chatId, normalizeLang(lang));
        } else if (MSG_DICTATE_CANCEL.equals(name)) {
            String sessionId = stringAt(args, 0);
            if (sessionId.equals(activeSessionId)) cancelDictation("cancelled");
        } else {
            Log.i(TAG, "Ignoring custom cmd key=" + name);
        }
    }

    private String normalizeLang(String lang) {
        return (lang == null || lang.trim().isEmpty() || "zh-CN".equalsIgnoreCase(lang.trim())) ? DEFAULT_ASR_LANG : lang.trim();
    }

    private void startDictation(String sessionId, String chatId, String lang) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendAsr(sessionId, "error", "permission");
            updateNotification("Microphone permission missing");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendAsr(sessionId, "error", "speech_unavailable");
            updateNotification("SpeechRecognizer unavailable");
            return;
        }
        cancelDictation("new-session");
        activeSessionId = sessionId;
        pendingSpeechLang = lang;
        updateNotification("Listening for chat " + chatId + " lang=" + lang);
        sendAsr(sessionId, "ready", "");

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { updateNotification("Ready for speech…"); }
            @Override public void onBeginningOfSpeech() { updateNotification("Hearing speech…"); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { updateNotification("Transcribing…"); }
            @Override public void onError(int error) {
                String code = "speech_error_" + error;
                updateNotification("ASR error " + code);
                sendAsr(sessionId, "error", code);
                cleanupRecognizer();
                activeSessionId = null;
            }
            @Override public void onResults(Bundle results) {
                String text = bestText(results);
                if (text.isEmpty()) sendAsr(sessionId, "error", "no_match");
                else sendAsr(sessionId, "final", text);
                sendAsr(sessionId, "end", "");
                cleanupRecognizer();
                activeSessionId = null;
                updateNotification("Final sent. Waiting for next request…");
            }
            @Override public void onPartialResults(Bundle partialResults) {
                String text = bestText(partialResults);
                if (!text.isEmpty()) sendAsr(sessionId, "partial", text);
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

    @Override public void onDestroy() {
        cleanupRecognizer();
        try { if (cxrLink != null) cxrLink.disconnect(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
