#!/usr/bin/env node
/**
 * Puente local de Sereno: Celular en Expo (Android / iOS / Web) <-> Emulador de Wear OS.
 *
 * - Escucha en TCP 19886 (PUERTO_TELEFONO): el emulador del reloj se conecta por 10.0.2.2:19886
 *   para hacer ping o enviar comandos (/sereno/cmd) y recibe como respuesta el último EstadoReloj.
 * - Empuja por TCP 19887 (PUERTO_RELOJ): envía cada nuevo EstadoReloj al reloj vía `adb forward`.
 * - Escucha en HTTP 19888: la app móvil en Expo Go publica el EstadoReloj (`POST /estado`) y
 *   recibe los comandos del reloj (`GET /poll`), sin saturar la laptop con un segundo emulador.
 */

const net = require('node:net');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');

const PUERTO_TELEFONO_TCP = 19886;
const PUERTO_RELOJ_TCP = 19887;
const PUERTO_EXPO_HTTP = 19888;
const TIPO_ESTADO = 1;
const TIPO_COMANDO = 2;

let ultimoEstadoBytes = Buffer.alloc(0);
let colaComandos = [];
let ultimoContactoReloj = 0;

function configurarAdbForward() {
  const posiblesAdb = [
    'adb',
    path.join(os.homedir(), 'Android', 'Sdk', 'platform-tools', 'adb'),
  ];
  for (const bin of posiblesAdb) {
    execFile(bin, ['devices'], (err, stdout) => {
      if (err || !stdout) return;
      const lineas = stdout
        .split('\n')
        .slice(1)
        .map((l) => l.trim().split(/\s+/)[0])
        .filter(Boolean);
      for (const serial of lineas) {
        execFile(bin, ['-s', serial, 'forward', `tcp:${PUERTO_RELOJ_TCP}`, `tcp:${PUERTO_RELOJ_TCP}`], () => {});
      }
    });
  }
}

function empujarAlReloj(bytes) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    socket.setTimeout(2000);
    socket.connect(PUERTO_RELOJ_TCP, '127.0.0.1', () => {
      const cabecera = Buffer.alloc(5);
      cabecera.writeUInt8(TIPO_ESTADO, 0);
      cabecera.writeInt32BE(bytes.length, 1);
      socket.write(Buffer.concat([cabecera, bytes]));
    });
    socket.on('data', () => {
      ultimoContactoReloj = Date.now();
      socket.destroy();
      resolve(true);
    });
    socket.on('error', () => {
      configurarAdbForward();
      resolve(false);
    });
    socket.on('timeout', () => {
      socket.destroy();
      resolve(false);
    });
  });
}

function pingReloj() {
  return new Promise((resolve) => {
    if (Date.now() - ultimoContactoReloj < 8000) {
      resolve(true);
      return;
    }
    const socket = new net.Socket();
    socket.setTimeout(800);
    socket.connect(PUERTO_RELOJ_TCP, '127.0.0.1', () => {
      ultimoContactoReloj = Date.now();
      socket.destroy();
      resolve(true);
    });
    socket.on('error', () => resolve(false));
    socket.on('timeout', () => {
      socket.destroy();
      resolve(false);
    });
  });
}

// 1) Servidor TCP 19886: recibe comandos y pings desde el emulador de Wear OS (10.0.2.2:19886)
const servidorTcp = net.createServer((cliente) => {
  ultimoContactoReloj = Date.now();
  cliente.setTimeout(2500);
  let buffer = Buffer.alloc(0);

  cliente.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    if (buffer.length < 5) return;
    const tipo = buffer.readUInt8(0);
    const longitud = buffer.readInt32BE(1);
    if (buffer.length < 5 + longitud) return;

    const datos = buffer.subarray(5, 5 + longitud);
    if (tipo === TIPO_COMANDO) {
      try {
        const cmd = JSON.parse(datos.toString('utf8'));
        console.log(`[Reloj -> Expo] Comando: ${cmd.tipo}${cmd.arg ? ` (${cmd.arg})` : ''}`);
        if (cmd.tipo !== 'PEDIR_ESTADO') {
          colaComandos.push(cmd);
          if (colaComandos.length > 50) colaComandos.shift();
        }
      } catch (e) {
        console.warn('Comando inválido del reloj:', e.message);
      }
      const resp = Buffer.alloc(4 + ultimoEstadoBytes.length);
      resp.writeInt32BE(ultimoEstadoBytes.length, 0);
      if (ultimoEstadoBytes.length > 0) {
        ultimoEstadoBytes.copy(resp, 4);
      }
      cliente.end(resp);
    } else {
      const resp = Buffer.alloc(4);
      resp.writeInt32BE(0, 0);
      cliente.end(resp);
    }
  });

  cliente.on('error', () => {});
  cliente.on('timeout', () => cliente.destroy());
});

servidorTcp.listen(PUERTO_TELEFONO_TCP, '0.0.0.0', () => {
  console.log(`TCP escuchando al reloj en 0.0.0.0:${PUERTO_TELEFONO_TCP} (empuja a 127.0.0.1:${PUERTO_RELOJ_TCP})`);
});

// 2) Servidor HTTP 19888: comunica la app móvil en Expo Go con el reloj
const servidorHttp = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === 'GET' && req.url === '/poll') {
    const conectado = await pingReloj();
    const comandos = colaComandos;
    colaComandos = [];
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, relojConectado: conectado, comandos }));
    return;
  }

  if (req.method === 'POST' && req.url === '/estado') {
    const trozos = [];
    req.on('data', (c) => trozos.push(c));
    req.on('end', async () => {
      ultimoEstadoBytes = Buffer.concat(trozos);
      const empujado = await empujarAlReloj(ultimoEstadoBytes);
      const conectado = empujado || (await pingReloj());
      const comandos = colaComandos;
      colaComandos = [];
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, relojConectado: conectado, comandos }));
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: false }));
});

servidorHttp.listen(PUERTO_EXPO_HTTP, '0.0.0.0', () => {
  configurarAdbForward();
  const ips = [];
  const redes = os.networkInterfaces();
  for (const nombre of Object.keys(redes)) {
    for (const red of redes[nombre] || []) {
      if (red.family === 'IPv4' && !red.internal) ips.push(red.address);
    }
  }
  console.log('==========================================================');
  console.log('Puente Sereno (Expo <-> Emulador Wear OS) activo');
  console.log(`  Local:   http://localhost:${PUERTO_EXPO_HTTP}`);
  for (const ip of ips) {
    console.log(`  Red LAN: http://${ip}:${PUERTO_EXPO_HTTP}  (detectado automáticamente en Expo Go)`);
  }
  console.log('==========================================================');
});
