package com.example.ntp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts {@link NetworkValidationService} with no user interaction:
 * <ul>
 *   <li>LOCKED_BOOT_COMPLETED - before the tablet is unlocked (component is direct-boot aware),</li>
 *   <li>BOOT_COMPLETED - after unlock, or immediately on tablets without a lock screen,</li>
 *   <li>MY_PACKAGE_REPLACED - after an APK update, so a reinstall needs no reboot or app launch.</li>
 * </ul>
 * These broadcasts are exempt from Android's background foreground-service start limits, and the
 * service's specialUse type is not one of the types Android 15 blocks from BOOT_COMPLETED.
 * Tag: NTPValidation
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "NTPValidation";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            String name = action.substring(action.lastIndexOf('.') + 1);
            Log.i(TAG, name + " received");
            NetworkValidationService.start(context, name);
            Log.i(TAG, "Boot validation scheduled via foreground service");
        }
    }
}
