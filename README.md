# Nebula Cosmos Max: volumen de la barra de sonido por HDMI ARC

Investigación (sin root) de por qué el mando del proyector **Anker Nebula Cosmos Max (D2150, Android TV 9)**
no controla el volumen de una barra de sonido conectada por **HDMI ARC**, aunque el sistema sí
puede hacerlo por HDMI-CEC (por ejemplo, al hacer cast desde YouTube).

> Estado: **causa encontrada y arreglo manual verificado.** Pendiente: hacerlo persistente.

## Arreglo rápido

```bash
adb connect IP_PROYECTOR:5555
adb shell settings put global sound_output_device 0
```

El firmware bloquea las teclas de volumen mientras `sound_output_device != 0`; con 0 siguen la ruta normal de
Android y llegan a la barra por CEC ([detalle](docs/02-descompilacion.md)). El sistema puede volver a ponerlo
a 1 al reiniciar o al encender la barra.

## Resumen

| Hallazgo | Evidencia |
|---|---|
| CEC y ARC funcionan; System Audio activo | `dumpsys hdmi_control`: `mArcEstablished: true`, `mSystemAudioActivated: true` |
| La barra (Yamaha YAS-207) responde a volumen por CEC | `media volume --stream 3 --adj raise` → `<User Control Pressed> 0x41` y `Report Audio Status` sube |
| El mando **no** es un dispositivo de entrada del kernel | `getevent -lt` no muestra teclas al pulsar |
| La app de sistema `com.oceanwing.receiver` recibe el mando por BLE e **inyecta** las teclas | logcat: `EventGenerator: inject code:24 action:0` |
| El firmware (personalización SDMC de `PhoneWindowManager`) descarta las teclas de volumen con ARC y muestra "Currently connected to ARC" | logcat `SDMC PWM: ...`; cadenas en `services.vdex` |
| Un servicio de accesibilidad no puede interceptarlas | Android no pasa eventos inyectados por los filtros de accesibilidad (`policyFlags=0x2b000000`) |
| El bloqueo depende de `Settings.Global sound_output_device` | Bytecode de `PhoneWindowManager.dispatchDirectAudioEvent` (extraído con `oatdump` del propio proyector) |
| Con `sound_output_device=0` el mando controla la barra | Verificado |

Detalle paso a paso en [docs/](docs/).

## Requisitos para reproducir

- Proyector con **Opciones de desarrollador** y **depuración ADB por red** activadas
  (Ajustes → Preferencias del dispositivo → Información → 7 toques en "Compilación").
- Un equipo con `adb` (platform-tools de Google) en la misma red.

## Contenido

- [`docs/`](docs/): bitácora de diagnóstico con comandos y salidas.
- [`arcvol/`](arcvol/): app mínima de accesibilidad (primer intento; no funciona por el motivo de arriba).

## Aviso

Todo se hace sin root y es reversible. No se redistribuye ningún binario del firmware.
