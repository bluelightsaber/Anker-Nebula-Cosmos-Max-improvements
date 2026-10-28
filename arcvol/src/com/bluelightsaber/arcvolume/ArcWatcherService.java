package com.bluelightsaber.arcvolume;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Keeps Settings.Global "sound_output_device" at 0.
 *
 * The Nebula firmware (SDMC changes to PhoneWindowManager.dispatchDirectAudioEvent) drops the
 * remote's volume keys and shows "Currently connected to ARC" whenever this flag is non-zero.
 * The HDMI service sets it to 1 every time ARC is established (boot, soundbar power-on), so we
 * watch it and reset it. With 0, volume keys follow the AOSP path and reach the soundbar over CEC.
 * See docs/02-decompilation.md.
 *
 * It is an accessibility service only so that Android starts it at boot and keeps it alive; it
 * observes no events. Writing a global setting needs WRITE_SECURE_SETTINGS, granted once over adb.
 */
public class ArcWatcherService extends AccessibilityService {

    private static final String TAG = "ArcVolume";
    private static final String KEY = "sound_output_device";

    private ContentObserver observer;

    @Override
    protected void onServiceConnected() {
        final ContentResolver cr = getContentResolver();
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                resetFlag(cr);
            }
        };
        cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer);
        resetFlag(cr); // it may already be 1 when we start
        Log.i(TAG, "watching " + KEY);
    }

    private static void resetFlag(ContentResolver cr) {
        int value = Settings.Global.getInt(cr, KEY, 0);
        if (value == 0) return; // also stops our own write from looping
        try {
            Settings.Global.putInt(cr, KEY, 0);
            Log.i(TAG, KEY + " " + value + " -> 0");
        } catch (SecurityException e) {
            Log.e(TAG, "missing WRITE_SECURE_SETTINGS; run: adb shell pm grant "
                    + "com.bluelightsaber.arcvolume android.permission.WRITE_SECURE_SETTINGS", e);
        }
    }

    @Override
    public void onDestroy() {
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
