# Correr Sereno: Celular en Expo (Android / iOS) + Reloj Wear OS en la laptop

Para no saturar la laptop corriendo dos emuladores de Android al mismo tiempo, la app del **teléfono (`mobile/`)** está hecha en **Expo (React Native / TypeScript)** y corre en tu celular físico con **Expo Go** (Android o iOS), mientras que en la laptop solo se ejecuta el **emulador del reloj (`wear/`)** y el **puente local (`mobile/puente.js`)**.

| Componente | Puerto | Descripción |
|---|---|---|
| Puente HTTP (Expo ↔ Laptop) | `19888` | La app de Expo publica el `EstadoReloj` (`POST /estado`) y recibe comandos (`GET /poll`) por Wi‑Fi local |
| Puente TCP (Reloj → Laptop) | `19886` | El emulador de Wear OS se conecta a `10.0.2.2:19886` para mandar comandos (`/sereno/cmd`) |
| Puente TCP (Laptop → Reloj) | `19887` | `puente.js` empuja cada cambio de estado al reloj mediante `adb forward tcp:19887 tcp:19887` |

## Pasos (Linux, esta máquina)

### 1. Compilar e iniciar el reloj Wear OS en el emulador

Opción rápida (levanta el emulador `Wear_OS_XL_Round`, instala `wear-debug.apk`, otorga permisos, redirige el puerto `19887` y abre la app automáticamente):

```bash
cd mobile
npm run reloj
```

O paso a paso manualmente:

```bash
cd Sereno
JBR=$(ls -d /var/lib/flatpak/app/com.google.AndroidStudio/x86_64/stable/*/files/extra/jbr | head -1)
./gradlew -Dorg.gradle.java.home=$JBR assembleDebug

E=~/Android/Sdk/emulator/emulator; A=~/Android/Sdk/platform-tools/adb
$E -avd Wear_OS_XL_Round -gpu swangle_indirect &
$A devices -l     # verificar el serial del reloj (ej. emulator-5554)

W=emulator-5554
$A -s $W install -r -g wear/build/outputs/apk/debug/wear-debug.apk
$A -s $W shell pm grant com.ralenday.sereno android.permission.ACCESS_LOCAL_NETWORK
$A -s $W forward tcp:19887 tcp:19887
$A -s $W shell am start -n com.ralenday.sereno/com.ralenday.sereno.wear.MainActivity
```

### 2. Iniciar el puente hacia el emulador y la app móvil en Expo

En una terminal levanta el puente TCP/HTTP (también ejecuta `adb forward tcp:19887 tcp:19887` automáticamente):

```bash
cd mobile
npm run puente
```

En otra terminal inicia Expo y escanea el código QR con **Expo Go** en tu celular Android o iOS (conectado a la misma red Wi‑Fi que tu laptop):

```bash
cd mobile
npm start
```

> La app de Expo detecta automáticamente la IP de tu laptop desde Metro. Si necesitas cambiarla manualmente, puedes hacerlo en la pestaña **⌚ Reloj** de la app.

## Ubicación

- **Ciudad simulada**: en la app del teléfono, los chips **"San Luis Río Colorado"** y **"Ciudad de México"** colocan tu ubicación en el centro de esa ciudad (sin requerir GPS físico).
- **GPS real del celular**: toca el chip **"📍 GPS"** para usar la ubicación real de tu teléfono en Android o iOS.
- **Simular caminata**: con una navegación activa, el botón **"Simular caminata"** en el teléfono recorre la ruta ~8x más rápido que caminando (incluye los tramos en transporte) y actualiza la flecha y vibraciones del reloj en tiempo real.

## Verificar el enlace

- En el teléfono, pestaña **⌚ Reloj**:
  - `● Reloj conectado`
  - `● Puente local activo (http://<IP-laptop>:19888)`
  - Bitácora en vivo con los comandos que manda el reloj.
- En la laptop: `adb -s $W logcat | grep SerenoEnlace` → `Estado recibido: N hoteles, M rutas, nav=…`

## Opcional: teléfono emulado con Expo Go (sin celular físico, p. ej. para capturas)

Se pueden correr los dos emuladores a la vez (necesita ~8 GB de RAM libres):

```bash
E=~/Android/Sdk/emulator/emulator; A=~/Android/Sdk/platform-tools/adb
$E -avd Wear_OS_XL_Round -port 5554 &      # reloj
$E -avd Medium_Phone_API_37.0 -port 5556 & # teléfono
P=emulator-5556

# Expo Go para el SDK del proyecto (58): https://expo.dev/go  → instalar el APK en el teléfono
$A -s $P install -r -g Expo-Go-58.x.apk

# El teléfono emulado llega a Metro y al puente por adb reverse (más estable que 10.0.2.2)
$A -s $P reverse tcp:8081 tcp:8081
$A -s $P reverse tcp:19888 tcp:19888

cd mobile && npm run puente &   # el puente solo redirige el 19887 al emulador que sea reloj
cd mobile && npm start          # en otra terminal
$A -s $P shell am start -a android.intent.action.VIEW -d exp://localhost:8081 host.exp.exponent
```

- Ubicación del teléfono emulado: `$A -s $P emu geo fix -114.7790 32.4840` (SLRC) o usa los chips de ciudad.
- Capturas: `$A -s $P exec-out screencap -p > telefono.png` y `$A -s emulator-5554 exec-out screencap -p > reloj.png`
  (o el botón de cámara del panel del emulador en Android Studio).
