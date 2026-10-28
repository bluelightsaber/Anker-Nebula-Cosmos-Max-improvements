# 3. Persistent fix

`settings put global sound_output_device 0` works, but the system HDMI service writes 1 again whenever the HDMI
audio state changes (`HdmiCecLocalDeviceTv.updateAudioDevicesStatus`).

## 3.1 When is the flag reset?

| Event | `sound_output_device` afterwards |
|---|---|
| Projector reboot (the soundbar follows it via CEC standby/wake-up) | **1**: the fix is lost. Logcat shows `SettingsProvider: Notifying for 0: content://settings/global/sound_output_device` during boot |
| Soundbar turned off and on with its own remote (projector stays on) | **1**: the fix is lost. Right after the soundbar's `<Initiate ARC>` / `<Set System Audio Mode>`, the audio HAL logs `ARC connect status =1` and `SettingsProvider` notifies `sound_output_device` |

So the value is rewritten every time ARC is (re)established, not only at boot: a one-off fix at boot is not
enough, something has to react to every change.

## 3.2 ARC Volume: a tiny watcher app

[`arcvol/`](../arcvol/) ([`ArcWatcherService.java`](../arcvol/src/com/bluelightsaber/arcvolume/ArcWatcherService.java)):

- registers a `ContentObserver` on `Settings.Global.sound_output_device` and puts it back to 0 whenever it
  becomes non-zero (and once at startup);
- needs `android.permission.WRITE_SECURE_SETTINGS`, a *development* permission that can be granted over adb
  without root;
- is declared as an **accessibility service** only so that Android starts it at boot and keeps it alive. It
  observes no events and filters no keys;
- logs to logcat with tag `ArcVolume`.

### Build

On any x86_64 Linux machine with `curl`, `unzip` and `python3` (no root, no Gradle):

```bash
cd arcvol && ./build.sh
```

The first run downloads a portable JDK 17 (Temurin) and a minimal Android SDK (build-tools 34, API 28) into the
parent folder, and generates a local signing key `arcvol.keystore`.

### Install

```bash
adb install -r arcvol/arcvol.apk
adb shell pm grant com.bluelightsaber.arcvolume android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services com.bluelightsaber.arcvolume/com.bluelightsaber.arcvolume.ArcWatcherService
adb shell settings put secure accessibility_enabled 1
```

> If you already use other accessibility services, append ours to the existing colon-separated list
> (`adb shell settings get secure enabled_accessibility_services`) instead of overwriting it.

### Check

```bash
adb shell settings put global sound_output_device 1
adb shell settings get global sound_output_device     # -> 0
adb logcat -d -s ArcVolume
#   I ArcVolume: watching sound_output_device
#   I ArcVolume: sound_output_device 1 -> 0
```

### Uninstall

```bash
adb uninstall com.bluelightsaber.arcvolume
adb shell settings put global sound_output_device 1   # optional; the system rewrites it anyway
```

## 3.3 Results

| Test | Result |
|---|---|
| Force `sound_output_device=1` over adb | Reset to 0 within ~3 ms (logcat) |
| Projector reboot | Service started at boot (`Start proc … for service com.bluelightsaber.arcvolume/.ArcWatcherService`, ~3 s after the system services). `sound_output_device` notified twice ~190 ms apart (system writes 1, ARC Volume writes 0). Remote controls the soundbar |
| Soundbar off/on with its own remote | Stays at 0; remote controls the soundbar. Volume keys now reach `adjustSuggestedStreamVolume(... dir:ADJUST_LOWER ...) from android/WindowManager` |

Note: on boot, `AccessibilityManagerService` logs `Ignoring non-encryption-aware service` before the user is
unlocked; this is harmless, the service is bound a few seconds later.

**The fix is persistent.** The app costs nothing while idle (it only wakes up when the setting changes).
