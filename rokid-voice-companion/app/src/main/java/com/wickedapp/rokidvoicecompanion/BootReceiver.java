package com.wickedapp.rokidvoicecompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "RokidVoiceCompanion";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        String token = context.getSharedPreferences(VoiceCompanionService.PREFS, Context.MODE_PRIVATE)
            .getString(VoiceCompanionService.PREF_AUTH_TOKEN, null);
        if (token == null || token.isEmpty()) {
            Log.i(TAG, "Boot/package event: no saved token; companion not auto-started");
            return;
        }
        Log.i(TAG, "Boot/package event: starting background companion service");
        VoiceCompanionService.start(context, token);
    }
}
