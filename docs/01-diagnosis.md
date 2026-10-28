# 1. Diagnosing the ARC volume problem

Setup: Nebula Cosmos Max (D2150), firmware
`Nebula/Nebula-Cosmos-Max/D2140-Cosmos:9/PI/20220425:user/release-keys`, Yamaha YAS-207 soundbar on HDMI 2 (ARC).
ADB over Wi-Fi on port 5555. Below, `adb` means `adb -s PROJECTOR_IP:5555`.

## 1.1 Connecting

```bash
adb connect PROJECTOR_IP:5555
```

The first time, the projector shows "Allow USB debugging?": accept it and tick "Always allow".
The Wi-Fi connection drops occasionally; just run `adb connect` again.

## 1.2 HDMI-CEC state

```bash
adb shell settings list global | grep -iE "hdmi|cec"
adb shell dumpsys hdmi_control
```

Relevant output:

```
hdmi_control_enabled=1
hdmi_system_audio_control_enabled=1
hdmi_system_audio_status_enabled=1

mArcEstablished: true
mSystemAudioActivated: true
CEC: logical_address: 0x05 device_type: 5 vendor_id: 41182 display_name: YAS-207 physical_address: 0x2000 port_id: 2
```

Conclusion: the projector sees the soundbar as a CEC audio system and System Audio mode is active.
The CEC history contains no volume messages, only `<Give Device Power Status>` once a minute.

## 1.3 What the remote does when pressing volume

30-second capture while pressing volume up:

```bash
adb shell "timeout 30 getevent -lt"      # kernel input events
adb shell logcat -v time                 # system log
adb shell dumpsys audio | grep -iE "adjust|setStreamVolume"
```

- `getevent`: **no** `EV_KEY`. The remote is not a kernel input device.
- logcat:
  ```
  I/NRHandler( 4297): NRBLERemoter is connected.
  D/EventGenerator( 4297): inject code:24 action:0
  D/WindowManager( 3529): interceptKeyTq keycode=24 interactive=true ... policyFlags=2b000000
  D/SDMC    ( 3529): PWM: Interval time is less than 1s, not update toast.
  ```
  PID 4297 is `com.oceanwing.receiver` (Anker system app): it receives the remote over BLE
  and injects the key in software.
- `dumpsys audio`: no `adjustStreamVolume`. The STREAM_MUSIC volume does not change.
- The screen shows the toast **"Currently connected to ARC"**.

## 1.4 Which paths do change the soundbar volume

| Command | Result |
|---|---|
| `adb shell input keyevent KEYCODE_VOLUME_UP` | Nothing; same "Currently connected to ARC" toast |
| `adb shell cmd media_session volume --stream 3 --adj raise` | Not available on Android 9 |
| `adb shell media volume --stream 3 --adj raise` | **Works** |

With `media volume`, the CEC history (`dumpsys hdmi_control`) shows:

```
[S] <User Control Pressed> src: 0, dst: 5, params: 41   # 0x41 = Volume Up
[R] <Report Audio Status>  src: 5, dst: 0, params: 16   # level 22
[S] <User Control Release> src: 0, dst: 5
```

Five raises take the soundbar from 0x16 (22) to 0x20 (32), and five lowers bring it back to 22. Confirmed by ear.

**Conclusion:** `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` is the working path (the same one casting uses).
The firmware intercepts the volume *keys* before they get there.

## 1.5 Attempt 1: accessibility service (does not work)

First version of the app in [`arcvol/`](../arcvol/) (since replaced by the watcher in [03](03-persistent-fix.md)): an `AccessibilityService` with `flagRequestFilterKeyEvents` that replaces the volume
keys with `adjustStreamVolume`. It is built without Gradle using [`arcvol/build.sh`](../arcvol/build.sh)
(portable JDK + build-tools 34 + API 28) and enabled over adb:

```bash
adb install -r arcvol.apk
adb shell settings put secure enabled_accessibility_services com.bluelightsaber.arcvolume/com.bluelightsaber.arcvolume.VolumeKeyService   # v1 class name
adb shell settings put secure accessibility_enabled 1
```

The service is bound (`dumpsys accessibility`), but it **never receives the keys**: Android only passes events
from real input devices through accessibility filters, not events injected with `injectInputEvent`
(flag `POLICY_FLAG_INJECTED`, included in `policyFlags=0x2b000000`).

To undo it:

```bash
adb uninstall com.bluelightsaber.arcvolume
```

## 1.6 A clue in the firmware

The strings of `/system/framework/oat/arm/services.vdex` include:

```
PWM: intercept audio event because deviceID != 0
PWM: Interval time is less than 1s, not update toast.
Currently connected to ARC
```

Next step: decompile that logic ([02-decompilation.md](02-decompilation.md)).
