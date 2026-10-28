# 1. Diagnóstico del volumen ARC

Entorno: Nebula Cosmos Max (D2150), firmware
`Nebula/Nebula-Cosmos-Max/D2140-Cosmos:9/PI/20220425:user/release-keys`, barra Yamaha YAS-207 en HDMI 2 (ARC).
ADB por wifi en el puerto 5555. En adelante, `adb` = `adb -s IP_PROYECTOR:5555`.

## 1.1 Conexión

```bash
adb connect IP_PROYECTOR:5555
```

La primera vez aparece en el proyector "¿Permitir depuración USB?": aceptar y marcar "Permitir siempre".
La conexión por wifi se cae a veces; basta con repetir `adb connect`.

## 1.2 Estado de HDMI-CEC

```bash
adb shell settings list global | grep -iE "hdmi|cec"
adb shell dumpsys hdmi_control
```

Resultado relevante:

```
hdmi_control_enabled=1
hdmi_system_audio_control_enabled=1
hdmi_system_audio_status_enabled=1

mArcEstablished: true
mSystemAudioActivated: true
CEC: logical_address: 0x05 device_type: 5 vendor_id: 41182 display_name: YAS-207 physical_address: 0x2000 port_id: 2
```

Conclusión: el proyector detecta la barra como sistema de audio CEC y el modo System Audio está activo.
En el historial CEC no aparece ningún mensaje de volumen, solo `<Give Device Power Status>` cada minuto.

## 1.3 Qué hace el mando al pulsar volumen

Captura de 30 s mientras se pulsa volumen +:

```bash
adb shell "timeout 30 getevent -lt"      # eventos del kernel
adb shell logcat -v time                 # registro del sistema
adb shell dumpsys audio | grep -iE "adjust|setStreamVolume"
```

- `getevent`: **ningún** `EV_KEY`. El mando no es un dispositivo de entrada del kernel.
- logcat:
  ```
  I/NRHandler( 4297): NRBLERemoter is connected.
  D/EventGenerator( 4297): inject code:24 action:0
  D/WindowManager( 3529): interceptKeyTq keycode=24 interactive=true ... policyFlags=2b000000
  D/SDMC    ( 3529): PWM: Interval time is less than 1s, not update toast.
  ```
  El PID 4297 es `com.oceanwing.receiver` (app de sistema de Anker): recibe el mando por BLE
  e inyecta la tecla por software.
- `dumpsys audio`: ningún `adjustStreamVolume`. El volumen de STREAM_MUSIC no cambia.
- En pantalla aparece el aviso **"Currently connected to ARC"**.

## 1.4 Qué rutas sí mueven el volumen de la barra

| Comando | Resultado |
|---|---|
| `adb shell input keyevent KEYCODE_VOLUME_UP` | Nada; mismo aviso "Currently connected to ARC" |
| `adb shell cmd media_session volume --stream 3 --adj raise` | No existe en Android 9 |
| `adb shell media volume --stream 3 --adj raise` | **Funciona** |

Con `media volume`, el historial CEC (`dumpsys hdmi_control`) muestra:

```
[S] <User Control Pressed> src: 0, dst: 5, params: 41   # 0x41 = Volume Up
[R] <Report Audio Status>  src: 5, dst: 0, params: 16   # nivel 22
[S] <User Control Release> src: 0, dst: 5
```

Cinco subidas llevan la barra de 0x16 (22) a 0x20 (32) y cinco bajadas la devuelven a 22. Confirmado de oído.

**Conclusión:** `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` es la ruta buena (la misma que usa el cast).
El firmware intercepta las *teclas* de volumen antes de que lleguen ahí.

## 1.5 Intento 1: servicio de accesibilidad (no funciona)

App [`arcvol/`](../arcvol/): un `AccessibilityService` con `flagRequestFilterKeyEvents` que cambia las teclas de
volumen por `adjustStreamVolume`. Se compila sin Gradle con [`arcvol/build.sh`](../arcvol/build.sh)
(JDK portable + build-tools 34 + API 28) y se activa por adb:

```bash
adb install -r arcvol.apk
adb shell settings put secure enabled_accessibility_services com.bluelightsaber.arcvolume/com.bluelightsaber.arcvolume.VolumeKeyService
adb shell settings put secure accessibility_enabled 1
```

El servicio queda activo (`dumpsys accessibility`), pero **nunca recibe las teclas**: Android solo pasa por los
filtros de accesibilidad los eventos de dispositivos reales, no los inyectados con `injectInputEvent`
(flag `POLICY_FLAG_INJECTED`, incluido en `policyFlags=0x2b000000`).

Para deshacerlo:

```bash
adb uninstall com.bluelightsaber.arcvolume
```

## 1.6 Pista en el firmware

En las cadenas de `/system/framework/oat/arm/services.vdex` aparecen:

```
PWM: intercept audio event because deviceID != 0
PWM: Interval time is less than 1s, not update toast.
Currently connected to ARC
```

Siguiente paso: descompilar esa lógica ([02-descompilacion.md](02-descompilacion.md)).
