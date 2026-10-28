package com.bluelightsaber.arcvolume;

import android.accessibilityservice.AccessibilityService;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * El firmware de Nebula descarta las teclas de volumen cuando hay barra por ARC
 * ("currently connected to ARC"). Este servicio las captura antes y llama a
 * AudioManager.adjustStreamVolume, que es la ruta que sí envía el volumen por CEC
 * (la misma que usa `media volume --adj raise` o el cast de YouTube).
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
                return false; // el resto de teclas sigue su camino normal
        }

        // Actuar solo al pulsar (y al mantener pulsado, salvo mute); consumir también el "soltar"
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
