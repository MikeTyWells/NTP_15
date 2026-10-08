package com.example.ntp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Invisible entry point for a kiosk app: starts the validation service and finishes immediately,
 * so the kiosk keeps the screen. Launching it also takes a freshly installed app out of Android's
 * "stopped" state, after which BOOT_COMPLETED is delivered on every boot.
 *
 *   adb shell am start -n com.example.ntp/.AutoStartActivity [--es ntp_server 10.10.10.5]
 *
 * Tag: NTPValidation
 */
public class AutoStartActivity extends Activity {
    private static final String TAG = "NTPValidation";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "AutoStartActivity launched");
        ValidationStore.applyConfigExtras(this, getIntent());
        NetworkValidationService.start(this, "kiosk auto-start");
        finish();
    }
}
