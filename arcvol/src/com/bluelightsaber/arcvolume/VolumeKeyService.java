package com.bluelightsaber.arcvolume;

import android.accessibilityservice.AccessibilityService;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The Nebula firmware drops the volume keys when a soundbar is on ARC
 * ("Currently connected to ARC"). This service captures them first and calls
 * AudioManager.adjustStreamVolume, which is the path that does send volume over CEC
 * (the same one used by `media volume --adj raise` or YouTube casting).
 *
 * Note: this does not work on the Cosmos Max, because the remote's keys are injected
 * by com.oceanwing.receiver and Android does not pass injected events through
 * accessibility filters. See docs/01-diagnosis.md.
 */
public class VolumeKeyService extends AccessibilityService {

    private AudioManager audio;

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int direction;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                direction = AudioManager.ADJUST_RAISE;
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                direction = AudioManager.ADJUST_LOWER;
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                direction = AudioManager.ADJUST_TOGGLE_MUTE;
                break;
            default:
                return false; // every other key follows its normal path
        }

        // Act only on key down (and on repeat, except mute); also consume the key up
        boolean isRepeat = event.getRepeatCount() > 0;
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && !(isRepeat && direction == AudioManager.ADJUST_TOGGLE_MUTE)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
