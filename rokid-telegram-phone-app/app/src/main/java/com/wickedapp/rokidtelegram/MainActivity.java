package com.wickedapp.rokidtelegram;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String TAG = "RokidTelegram";
    private static final String VERSION = "v0.1.0-phone-webtg-voice";
    private static final String TELEGRAM_URL = "https://web.telegram.org/k/";

    private WebView webView;
    private TextView title;
    private TextView status;
    private TextView hint;
    private SpeechRecognizer speechRecognizer;
    private boolean listening = false;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureWebView();
        status("loading Telegram Web…");
        webView.loadUrl(TELEGRAM_URL);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(12, 8, 12, 8);
        overlay.setBackgroundColor(Color.argb(210, 5, 8, 6));
        overlay.setFocusable(false);
        overlay.setClickable(false);

        title = new TextView(this);
        title.setText("Rokid Telegram Phone  " + VERSION);
        title.setTextColor(Color.rgb(117, 255, 147));
        title.setTextSize(12);
        title.setGravity(Gravity.START);
        overlay.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setGravity(Gravity.START);
        overlay.addView(status);

        hint = new TextView(this);
        hint.setText("Keyboard = type · Ctrl/Alt+Enter or Search = voice · Back = exit");
        hint.setTextColor(Color.rgb(166, 217, 176));
        hint.setTextSize(12);
        hint.setGravity(Gravity.START);
        overlay.addView(hint);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        );
        root.addView(overlay, lp);
        setContentView(root);

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(110);
        s.setUserAgentString(s.getUserAgentString() + " RokidTelegram/" + VERSION);
        if (android.os.Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                status("Telegram Web loaded");
                view.requestFocus();
                focusTelegramComposer();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                status("load error: " + error.getDescription());
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });
    }

    private void status(String text) {
        Log.i(TAG, text);
        if (status != null) status.setText(text);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            int code = event.getKeyCode();
            boolean modifiedEnter = (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)
                && (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed());
            boolean voiceKey = code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST;
            if (modifiedEnter || voiceKey) {
                startVoiceInput();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void startVoiceInput() {
        if (listening) {
            status("already listening…");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 42);
            status("mic permission requested");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("speech recognizer unavailable");
            return;
        }
        try {
            if (speechRecognizer != null) speechRecognizer.destroy();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { listening = true; status("listening…"); }
                @Override public void onBeginningOfSpeech() { status("hearing speech…"); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { status("transcribing…"); }
                @Override public void onError(int error) {
                    listening = false;
                    status("voice error: " + voiceErrorName(error));
                    cleanupRecognizer();
                }
                @Override public void onResults(Bundle results) {
                    listening = false;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = matches == null || matches.isEmpty() ? "" : matches.get(0);
                    status("dictated: " + text);
                    injectTelegramText(text);
                    cleanupRecognizer();
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) status("partial: " + matches.get(0));
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Telegram dictation");
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900);
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            status("voice exception: " + e.getMessage());
            cleanupRecognizer();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput();
        } else {
            status("mic permission denied");
        }
    }

    private void cleanupRecognizer() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
    }

    private String voiceErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio";
            case SpeechRecognizer.ERROR_CLIENT: return "client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "permission";
            case SpeechRecognizer.ERROR_NETWORK: return "network";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "busy";
            case SpeechRecognizer.ERROR_SERVER: return "server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech";
            default: return "unknown " + error;
        }
    }

    private void focusTelegramComposer() {
        webView.postDelayed(() -> webView.evaluateJavascript("(function(){\n" +
            "var nodes=[].slice.call(document.querySelectorAll('[contenteditable=\"true\"],div[role=\"textbox\"],textarea,input'));\n" +
            "var el=nodes.reverse().find(function(n){var r=n.getBoundingClientRect();return r.width>20&&r.height>10&&r.top>window.innerHeight*0.35;});\n" +
            "if(el){el.focus(); return 'focused';}\n" +
            "return 'no-composer';\n" +
            "})()", v -> Log.i(TAG, "focusComposer=" + v)), 900);
    }

    private void injectTelegramText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String escaped = raw.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
        String js = "(function(){\n" +
            "var text='" + escaped + "';\n" +
            "function visible(el){var r=el.getBoundingClientRect();return r.width>20&&r.height>10;}\n" +
            "var candidates=[].slice.call(document.querySelectorAll('[contenteditable=\"true\"],div[role=\"textbox\"],textarea,input'));\n" +
            "var el=candidates.reverse().find(function(n){return visible(n)&&n.getBoundingClientRect().top>window.innerHeight*0.30;}) || candidates.reverse().find(visible);\n" +
            "if(!el) return 'no-composer';\n" +
            "el.focus();\n" +
            "if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){el.value=(el.value?el.value+' ':'')+text; el.dispatchEvent(new Event('input',{bubbles:true})); return 'inserted-input';}\n" +
            "var sel=window.getSelection(); var range=document.createRange(); range.selectNodeContents(el); range.collapse(false); sel.removeAllRanges(); sel.addRange(range);\n" +
            "document.execCommand('insertText', false, (el.innerText&&el.innerText.trim()? ' ':'') + text);\n" +
            "el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));\n" +
            "return 'inserted-contenteditable';\n" +
            "})()";
        webView.evaluateJavascript(js, value -> {
            Log.i(TAG, "inject result=" + value);
            status("insert result: " + value);
            focusTelegramComposer();
        });
    }

    @Override protected void onDestroy() {
        cleanupRecognizer();
        super.onDestroy();
    }
}
