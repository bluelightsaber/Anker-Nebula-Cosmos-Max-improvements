# 2. Decompiling the volume logic (no root)

Goal: understand why `PhoneWindowManager` drops the volume keys while on ARC and shows
"Currently connected to ARC".

## 2.1 Extracting the code of `services.jar`

On Android 9 `services.jar` is *preoptimised*: the jar only holds the manifest, and the code lives in
`/system/framework/oat/arm/services.vdex` in **CompactDex** format (`cdex001`). The most reliable approach is the
`oatdump` binary shipped on the projector itself, which understands its exact format:

```bash
adb shell "mkdir -p /data/local/tmp/dex && oatdump --oat-file=/system/framework/oat/arm/services.odex --export-dex-to=/data/local/tmp/dex"
adb pull /data/local/tmp/dex/services.jar_export.dex
```

(It takes a few minutes.) The `dexdump` from the SDK build-tools (34.0.0) reads CompactDex fine:

```bash
$ANDROID_SDK/build-tools/34.0.0/dexdump -d services.jar_export.dex > services.dump.txt
grep -n "Currently connected to ARC\|intercept audio event" services.dump.txt
```

## 2.2 `PhoneWindowManager.dispatchDirectAudioEvent(KeyEvent)`

This is where volume keys end up on Android TV. Summarised from the bytecode:

```java
void dispatchDirectAudioEvent(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return;

    // Added by the vendor (SDMC):
    if (Settings.Global.getInt(cr, "sound_output_device", 0) != 0) {
        Log.d("SDMC", "PWM: intercept audio event because deviceID != 0");
        if (uptimeMillis() - mHintToastShowTime < 1000) {
            Log.d("SDMC", "PWM: Interval time is less than 1s, not update toast.");
            return;
        }
        mHintToastShowTime = uptimeMillis();
        mHintToast.cancel();
        mHandler.sendMessage(mHandler.obtainMessage(32)); // shows "Currently connected to ARC"
        return;                                           // <- the key is dropped
    }

    // Regular AOSP code:
    switch (event.getKeyCode()) {
        case KEYCODE_VOLUME_UP:   getAudioService().adjustSuggestedStreamVolume(ADJUST_RAISE, USE_DEFAULT_STREAM_TYPE, ...); break;
        case KEYCODE_VOLUME_DOWN: getAudioService().adjustSuggestedStreamVolume(ADJUST_LOWER, ...); break;
        case KEYCODE_VOLUME_MUTE: ...
    }
}
```

In other words: if the global setting **`sound_output_device`** is non-zero, the firmware **deliberately blocks**
the volume keys. If it is 0, the AOSP path (`adjustSuggestedStreamVolume`) runs, which with System Audio active
sends the volume to the soundbar over CEC (the same as `media volume --adj raise`, see
[01-diagnosis.md](01-diagnosis.md#14-which-paths-do-change-the-soundbar-volume)).

## 2.3 Who writes `sound_output_device`

```bash
adb shell settings get global sound_output_device    # -> 1 with the soundbar connected
```

It only appears in three places in `services.jar`:

| Method | Usage |
|---|---|
| `HdmiControlService.<init>` | `writeBooleanSetting("sound_output_device", …)` at startup |
| `HdmiCecLocalDeviceTv.updateAudioDevicesStatus(boolean on)` | `writeBooleanSetting("sound_output_device", on)` and mutes/unmutes the internal speaker with `AudioManager.setParameters("speaker_mute=…")` |
| `PhoneWindowManager.dispatchDirectAudioEvent` | **reads** the value to decide whether to block the keys |

Outside `services.jar`, only `/system/priv-app/TvSettings/TvSettings.apk` contains it.

Conclusion: `sound_output_device` is a **status flag** ("audio output is an ARC device"), not a preference.
Muting the internal speaker does not depend on it (it goes through `setParameters`), so setting it to 0 should
only affect the key blocking (and perhaps what TvSettings displays).
It is rewritten to 1 whenever the HDMI audio state changes (boot, soundbar power-on…).

## 2.4 Test: setting `sound_output_device` to 0

```bash
adb shell settings put global sound_output_device 0
```

Result: **the remote controls the soundbar volume** and the "Currently connected to ARC" toast no longer appears.
Sound still comes out of the soundbar.

To undo: `adb shell settings put global sound_output_device 1`.

Limitation: since the HDMI service rewrites the value when the audio state changes (boot, soundbar power-on…),
the one-command fix is not permanent. See [03-persistent-fix.md](03-persistent-fix.md).
