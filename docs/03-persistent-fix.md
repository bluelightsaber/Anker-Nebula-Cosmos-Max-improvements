# 3. Persistent fix (in progress)

`settings put global sound_output_device 0` works, but the system HDMI service writes 1 again whenever the HDMI
audio state changes (`HdmiCecLocalDeviceTv.updateAudioDevicesStatus`), e.g. at boot or when the soundbar is
turned on.

Observations:

| Event | `sound_output_device` afterwards |
|---|---|
| Projector reboot (the soundbar follows it via CEC standby/wake-up) | **1**: the fix is lost. Logcat shows `SettingsProvider: Notifying for 0: content://settings/global/sound_output_device` during boot |
| Soundbar turned off and on with its own remote (projector stays on) | **1**: the fix is lost. Right after the soundbar's `<Initiate ARC>` / `<Set System Audio Mode>`, the audio HAL logs `ARC connect status =1` and `SettingsProvider` notifies `sound_output_device` |

So the value is rewritten every time ARC is (re)established, not only at boot: a one-off fix at boot is not
enough, the watcher must react to every change.

Plan:

1. Check when it goes back to 1 (reboot, soundbar off/on, input switch).
2. Turn the [`arcvol/`](../arcvol/) app into a minimal watcher:
   - a `ContentObserver` on `Settings.Global.sound_output_device` that resets it to 0 when it changes;
   - the `android.permission.WRITE_SECURE_SETTINGS` permission, granted once over adb (no root):
     `adb shell pm grant com.bluelightsaber.arcvolume android.permission.WRITE_SECURE_SETTINGS`;
   - auto-start when the projector boots (`BOOT_COMPLETED`), or keep the accessibility service only so that
     the system keeps it alive.
