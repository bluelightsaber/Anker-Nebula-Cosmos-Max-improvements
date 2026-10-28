# Nebula Cosmos Max: soundbar volume over HDMI ARC

A root-free investigation into why the remote of the **Anker Nebula Cosmos Max (D2150, Android TV 9)**
projector does not control the volume of a soundbar connected over **HDMI ARC**, even though the system
can do it over HDMI-CEC (e.g. when casting from YouTube).

> Status: **root cause found and manual fix verified.** To do: make it persistent.

## Quick fix

```bash
adb connect PROJECTOR_IP:5555
adb shell settings put global sound_output_device 0
```

The firmware blocks the volume keys while `sound_output_device != 0`; with 0 they follow the normal Android
path and reach the soundbar over CEC ([details](docs/02-decompilation.md)). The system may set it back to 1
after a reboot or when the soundbar is turned on.

## Summary

| Finding | Evidence |
|---|---|
| CEC and ARC work; System Audio is active | `dumpsys hdmi_control`: `mArcEstablished: true`, `mSystemAudioActivated: true` |
| The soundbar (Yamaha YAS-207) responds to CEC volume | `media volume --stream 3 --adj raise` → `<User Control Pressed> 0x41` and `Report Audio Status` goes up |
| The remote is **not** a kernel input device | `getevent -lt` shows no keys when pressing |
| The system app `com.oceanwing.receiver` receives the remote over BLE and **injects** the keys | logcat: `EventGenerator: inject code:24 action:0` |
| The firmware (SDMC customisation of `PhoneWindowManager`) drops volume keys while on ARC and shows "Currently connected to ARC" | logcat `SDMC PWM: ...`; strings in `services.vdex` |
| An accessibility service cannot intercept them | Android does not pass injected events through accessibility filters (`policyFlags=0x2b000000`) |
| The block depends on `Settings.Global sound_output_device` | Bytecode of `PhoneWindowManager.dispatchDirectAudioEvent` (extracted with the projector's own `oatdump`) |
| With `sound_output_device=0` the remote controls the soundbar | Verified |

Step-by-step details in [docs/](docs/).

## Requirements to reproduce

- Projector with **Developer options** and **network ADB debugging** enabled
  (Settings → Device Preferences → About → tap "Build" 7 times).
- A computer with `adb` (Google platform-tools) on the same network.

## Contents

- [`docs/`](docs/): diagnostic log with commands and outputs.
- [`arcvol/`](arcvol/): minimal accessibility app (first attempt; does not work for the reason above).

## Disclaimer

Everything is done without root and is reversible. No firmware binaries are redistributed.
