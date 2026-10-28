# 2. Descompilación de la lógica de volumen (sin root)

Objetivo: entender por qué `PhoneWindowManager` descarta las teclas de volumen con ARC y muestra
"Currently connected to ARC".

## 2.1 Extraer el código de `services.jar`

En Android 9 `services.jar` está *preoptimizado*: el jar solo tiene el manifest y el código está en
`/system/framework/oat/arm/services.vdex` en formato **CompactDex** (`cdex001`). Lo más fiable es usar el
`oatdump` que trae el propio proyector, que entiende su formato exacto:

```bash
adb shell "mkdir -p /data/local/tmp/dex && oatdump --oat-file=/system/framework/oat/arm/services.odex --export-dex-to=/data/local/tmp/dex"
adb pull /data/local/tmp/dex/services.jar_export.dex
```

(Tarda unos minutos.) El `dexdump` de las build-tools del SDK (34.0.0) lee CompactDex sin problemas:

```bash
$ANDROID_SDK/build-tools/34.0.0/dexdump -d services.jar_export.dex > services.dump.txt
grep -n "Currently connected to ARC\|intercept audio event" services.dump.txt
```

## 2.2 `PhoneWindowManager.dispatchDirectAudioEvent(KeyEvent)`

Es el método al que llegan las teclas de volumen en Android TV. Resumido del bytecode:

```java
void dispatchDirectAudioEvent(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return;

    // Añadido por el fabricante (SDMC):
    if (Settings.Global.getInt(cr, "sound_output_device", 0) != 0) {
        Log.d("SDMC", "PWM: intercept audio event because deviceID != 0");
        if (uptimeMillis() - mHintToastShowTime < 1000) {
            Log.d("SDMC", "PWM: Interval time is less than 1s, not update toast.");
            return;
        }
        mHintToastShowTime = uptimeMillis();
        mHintToast.cancel();
        mHandler.sendMessage(mHandler.obtainMessage(32)); // muestra "Currently connected to ARC"
        return;                                           // <- la tecla se descarta
    }

    // Código AOSP normal:
    switch (event.getKeyCode()) {
        case KEYCODE_VOLUME_UP:   getAudioService().adjustSuggestedStreamVolume(ADJUST_RAISE, USE_DEFAULT_STREAM_TYPE, ...); break;
        case KEYCODE_VOLUME_DOWN: getAudioService().adjustSuggestedStreamVolume(ADJUST_LOWER, ...); break;
        case KEYCODE_VOLUME_MUTE: ...
    }
}
```

Es decir: si el ajuste global **`sound_output_device`** vale distinto de 0, el firmware **bloquea a propósito**
las teclas de volumen. Si vale 0, sigue la ruta AOSP (`adjustSuggestedStreamVolume`), que con System Audio
activo envía el volumen a la barra por CEC (lo mismo que `media volume --adj raise`, ver
[01-diagnostico.md](01-diagnostico.md#14-qué-rutas-sí-mueven-el-volumen-de-la-barra)).

## 2.3 Quién escribe `sound_output_device`

```bash
adb shell settings get global sound_output_device    # -> 1 con la barra conectada
```

En `services.jar` solo aparece en tres sitios:

| Método | Uso |
|---|---|
| `HdmiControlService.<init>` | `writeBooleanSetting("sound_output_device", …)` al arrancar |
| `HdmiCecLocalDeviceTv.updateAudioDevicesStatus(boolean on)` | `writeBooleanSetting("sound_output_device", on)` y silencia/activa el altavoz interno con `AudioManager.setParameters("speaker_mute=…")` |
| `PhoneWindowManager.dispatchDirectAudioEvent` | **lee** el valor para decidir si bloquea las teclas |

Fuera de `services.jar` solo lo contiene `/system/priv-app/TvSettings/TvSettings.apk`.

Conclusión: `sound_output_device` es un **indicador de estado** ("la salida de audio es un dispositivo ARC"),
no una preferencia. El silenciado del altavoz interno no depende de él (va por `setParameters`), así que ponerlo
a 0 solo debería afectar al bloqueo de teclas (y quizá a lo que muestre TvSettings).
Se reescribe a 1 cada vez que cambia el estado del audio HDMI (arranque, encendido de la barra…).

## 2.4 Prueba: poner `sound_output_device` a 0

```bash
adb shell settings put global sound_output_device 0
```

Resultado: **el mando controla el volumen de la barra** y deja de aparecer "Currently connected to ARC".
El sonido sigue saliendo por la barra.

Para deshacerlo: `adb shell settings put global sound_output_device 1`.

Limitación: como el servicio HDMI reescribe el valor cuando cambia el estado del audio (arranque, encendido de
la barra…), el arreglo con un solo comando no es permanente. Ver [03-arreglo-persistente.md](03-arreglo-persistente.md).
