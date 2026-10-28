# 3. Arreglo persistente (en curso)

`settings put global sound_output_device 0` funciona, pero el servicio HDMI del sistema vuelve a escribir 1
cuando cambia el estado del audio HDMI (`HdmiCecLocalDeviceTv.updateAudioDevicesStatus`), por ejemplo al
arrancar o al encender la barra.

Plan:

1. Comprobar en qué momentos vuelve a 1 (reinicio, apagar/encender la barra, cambiar de entrada).
2. Reconvertir la app [`arcvol/`](../arcvol/) en un vigilante mínimo:
   - un `ContentObserver` sobre `Settings.Global.sound_output_device` que lo devuelve a 0 cuando cambia;
   - permiso `android.permission.WRITE_SECURE_SETTINGS`, concedido una vez por adb (sin root):
     `adb shell pm grant com.bluelightsaber.arcvolume android.permission.WRITE_SECURE_SETTINGS`;
   - arranque automático al encender el proyector (`BOOT_COMPLETED`), o mantener el servicio de accesibilidad
     solo para que el sistema lo mantenga vivo.
