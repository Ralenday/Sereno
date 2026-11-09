# Correr Sereno con 2 emuladores (teléfono + reloj)

Igual que MemoFlash: en emuladores sin cuenta de Google no hay emparejamiento de la Wearable Data
Layer, así que `Enlace.kt` usa además un **puente TCP** por el host (`10.0.2.2`).

| Dispositivo | Escucha en | Comando |
|---|---|---|
| Teléfono | `19886` | `adb -s <telefono> forward tcp:19886 tcp:19886` |
| Reloj | `19887` | `adb -s <reloj> forward tcp:19887 tcp:19887` |

## Pasos (Linux, esta máquina)

```bash
cd Sereno
JBR=$(ls -d /var/lib/flatpak/app/com.google.AndroidStudio/x86_64/stable/*/files/extra/jbr | head -1)
./gradlew -Dorg.gradle.java.home=$JBR assembleDebug        # compilar (el JDK del sistema no trae javac)

E=~/Android/Sdk/emulator/emulator; A=~/Android/Sdk/platform-tools/adb
$E -avd Medium_Phone_API_37.0 -gpu swangle_indirect &      # swiftshader truena (exit 139)
$E -avd Wear_OS_XL_Round      -gpu swangle_indirect &
$A devices -l     # sdk_gphone = teléfono, sdk_gwear = reloj (aquí: 5554 teléfono, 5556 reloj)

P=emulator-5554; W=emulator-5556
$A -s $P install -r -g mobile/build/outputs/apk/debug/mobile-debug.apk
$A -s $W install -r -g wear/build/outputs/apk/debug/wear-debug.apk
# Android 17 (API 37): permiso de red local para hablar con 10.0.2.2
$A -s $P shell pm grant com.ralenday.sereno android.permission.ACCESS_LOCAL_NETWORK
$A -s $W shell pm grant com.ralenday.sereno android.permission.ACCESS_LOCAL_NETWORK
$A -s $P forward tcp:19886 tcp:19886
$A -s $W forward tcp:19887 tcp:19887

$A -s $P shell am start -n com.ralenday.sereno/com.ralenday.sereno.mobile.ui.MainActivity
$A -s $W shell am start -n com.ralenday.sereno/com.ralenday.sereno.wear.MainActivity
```

## Ubicación

- **GPS del emulador**: `adb -s $P emu geo fix <lon> <lat>` (¡longitud primero!).
  - Zócalo CDMX: `-99.1332 19.4326` · Polanco: `-99.1910 19.4330`
  - Centro SLRC: `-114.7790 32.4840`
- **Ciudad simulada**: en la app, chips "San Luis Río Colorado / Ciudad de México" ponen tu
  ubicación en el centro de esa ciudad (ignora el GPS).
- **Simular caminata**: con una navegación activa, botón "Simular caminata" en el teléfono: recorre la
  ruta ~8x más rápido que caminando (incluye los tramos en transporte) e ignora el GPS real.

## Demo recomendada (≈3 min)

1. GPS en Polanco → "Buscar" con la frase de ejemplo (hotel barato cerca del centro).
2. En el **reloj**: Hoteles → elegir uno → aparecen las rutas (Metro/Metrobús + costo + total con noche).
3. Tocar una ruta en el reloj → el reloj salta a la flecha de navegación.
4. En el teléfono: "Simular caminata" → el reloj vibra en cada giro, avisa "Prepárate: bajas en …" y "Llegaste".
5. En el reloj: SOS (mantener presionado) → alerta en ambos.
6. Cambiar a San Luis R.C. → se ven los hoteles y las 4 rutas locales de combi ($14 general / $7 descuento) y taxi por zona (desde $45).

## Verificar el enlace

- Teléfono, pestaña **Reloj**: "● Reloj conectado" y la bitácora con los comandos que manda el reloj.
- `adb -s $W logcat | grep SerenoEnlace` → `Estado recibido: N hoteles, M rutas, nav=…`
