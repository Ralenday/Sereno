#!/usr/bin/env node
/**
 * Levanta el emulador del reloj Wear OS (Wear_OS_XL_Round), instala wear-debug.apk,
 * configura los permisos/puertos y abre Sereno en el reloj.
 */

const { spawn, execFileSync } = require('node:child_process');
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');

const sdk = path.join(os.homedir(), 'Android', 'Sdk');
const emulatorBin = path.join(sdk, 'emulator', 'emulator');
const adbBin = path.join(sdk, 'platform-tools', 'adb');
const apkRuta = path.resolve(__dirname, '..', 'wear', 'build', 'outputs', 'apk', 'debug', 'wear-debug.apk');
const avdNombre = process.env.SERENO_AVD || 'Wear_OS_XL_Round';

function obtenerDispositivos() {
  try {
    const out = execFileSync(adbBin, ['devices'], { encoding: 'utf8' });
    return out
      .split('\n')
      .slice(1)
      .map((l) => l.trim().split(/\s+/))
      .filter((p) => p.length >= 2 && p[1] === 'device')
      .map((p) => p[0]);
  } catch {
    return [];
  }
}

async function esperar(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function main() {
  let dispositivos = obtenerDispositivos();
  if (dispositivos.length === 0) {
    console.log(`Iniciando emulador de reloj (${avdNombre})...`);
    const proc = spawn(emulatorBin, ['-avd', avdNombre, '-gpu', 'swangle_indirect'], {
      detached: true,
      stdio: 'ignore',
    });
    proc.unref();

    console.log('Esperando a que el emulador del reloj encienda...');
    for (let i = 0; i < 60; i++) {
      await esperar(2000);
      dispositivos = obtenerDispositivos();
      if (dispositivos.length > 0) break;
    }
  }

  const serial = dispositivos[0];
  if (!serial) {
    console.error('No se detectó el emulador tras esperar. Revisa Android Studio / AVD.');
    process.exit(1);
  }
  console.log(`Reloj detectado: ${serial}`);

  // Esperar a que termine de arrancar Android en el reloj
  for (let i = 0; i < 45; i++) {
    try {
      const boot = execFileSync(adbBin, ['-s', serial, 'shell', 'getprop', 'sys.boot_completed'], {
        encoding: 'utf8',
      }).trim();
      if (boot === '1') break;
    } catch {}
    await esperar(2000);
  }

  if (fs.existsSync(apkRuta)) {
    console.log('Instalando wear-debug.apk en el reloj...');
    execFileSync(adbBin, ['-s', serial, 'install', '-r', '-g', apkRuta], { stdio: 'inherit' });
  }

  try {
    execFileSync(adbBin, [
      '-s',
      serial,
      'shell',
      'pm',
      'grant',
      'com.ralenday.sereno',
      'android.permission.ACCESS_LOCAL_NETWORK',
    ]);
  } catch {}

  execFileSync(adbBin, ['-s', serial, 'forward', 'tcp:19887', 'tcp:19887'], { stdio: 'inherit' });
  execFileSync(
    adbBin,
    ['-s', serial, 'shell', 'am', 'start', '-n', 'com.ralenday.sereno/com.ralenday.sereno.wear.MainActivity'],
    { stdio: 'inherit' },
  );
  console.log('Reloj Wear OS listo y enlazado en el puerto TCP 19887.');
}

main();
