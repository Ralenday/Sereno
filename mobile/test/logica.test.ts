import assert from 'node:assert/strict';
import test from 'node:test';
import { Geo } from '../src/core/Geo.ts';
import { estadoRelojAJson } from '../src/core/Modelos.ts';
import { Ciudades } from '../src/datos/Ciudades.ts';
import { type Linea, Osm, type Parada } from '../src/datos/Osm.ts';
import { Ruteo } from '../src/datos/Ruteo.ts';
import { Transporte } from '../src/datos/Transporte.ts';
import { Interprete } from '../src/logica/Interprete.ts';

test('Interprete: frase del ejemplo', () => {
  const p = Interprete.interpretar('Necesito hotel barato cerca del centro y transporte público');
  assert.deepEqual(Array.from(p.tipos), ['hotel']);
  assert.equal(p.barato, true);
  assert.equal(p.cercaDelCentro, true);
  assert.equal(p.orden, 'PRECIO');
  assert.equal(p.cercaDe, null);
});

test('Interprete: presupuesto y lugar', () => {
  const p = Interprete.interpretar('Hostal de menos de 600 cerca del Zócalo');
  assert.deepEqual(Array.from(p.tipos), ['hostel']);
  assert.equal(p.presupuestoMax, 600);
  assert.equal(p.cercaDe, 'zocalo');
});

test('Interprete: mejor valorado', () => {
  assert.equal(Interprete.interpretar('Hotel mejor valorado').orden, 'VALORACION');
});

test('Ciudades: detección y tarifas de taxi SLRC por zona', () => {
  assert.equal(Ciudades.detectar({ lat: 32.46, lon: -114.77 })?.id, 'slrc');
  assert.equal(Ciudades.detectar({ lat: 19.40, lon: -99.15 })?.id, 'cdmx');
  assert.equal(Ciudades.detectar({ lat: 32.62, lon: -115.45 }), null);
  assert.equal(Ciudades.paraGps({ lat: 32.62, lon: -115.45 }, 'Mexicali (GPS)').id, 'gps_actual');

  const centro = Ciudades.SLRC.centro;
  const cerca = { lat: 32.481, lon: -114.775 };
  const lejos = { lat: 32.446, lon: -114.735 };
  assert.equal(Ciudades.costoTaxi(Ciudades.SLRC, 1000, centro, cerca), 45.0);
  assert.ok(Ciudades.costoTaxi(Ciudades.SLRC, 5000, centro, lejos) >= 75.0);
  assert.equal(Ciudades.SLRC.tarifaDescuento, 7.0);
});

test('Transporte: planea Metro con transbordo y tarifa única', () => {
  const parada = (id: number, nombre: string, lat: number, lon: number): Parada => ({
    id,
    nombre,
    punto: { lat, lon },
  });
  const l1: Linea = {
    id: 1,
    ref: '1',
    nombre: 'Línea 1',
    modo: 'METRO',
    red: 'Metro',
    paradas: [
      parada(1, 'A', 19.40, -99.20),
      parada(2, 'B', 19.40, -99.18),
      parada(3, 'Cruce', 19.40, -99.16),
    ],
  };
  const l2: Linea = {
    id: 2,
    ref: '2',
    nombre: 'Línea 2',
    modo: 'METRO',
    red: 'Metro',
    paradas: [
      parada(4, 'Cruce2', 19.4005, -99.16),
      parada(5, 'C', 19.42, -99.16),
      parada(6, 'D', 19.44, -99.16),
    ],
  };
  const rutas = new Transporte(Ciudades.CDMX).planear(
    { lat: 19.401, lon: -99.201 },
    { lat: 19.441, lon: -99.161 },
    [l1, l2],
  );
  const metro = rutas.find((r) => r.tramos.some((t) => t.modo === 'METRO'))!;
  assert.ok(metro);
  assert.equal(metro.transbordos, 1);
  assert.equal(metro.costo, 5.0);
  assert.ok(rutas.some((r) => r.tramos.length === 1 && r.tramos[0].modo === 'TAXI'));
});

test('Ruteo: parsea respuesta OSRM y polilíneas', () => {
  const json = {
    code: 'Ok',
    routes: [
      {
        distance: 250.4,
        duration: 180.0,
        geometry: '_p~iF~ps|U_ulLnnqC',
        legs: [
          {
            steps: [
              { name: 'Madero', distance: 120, maneuver: { type: 'depart', location: [-99.13, 19.43] } },
              { name: '', distance: 130, maneuver: { type: 'turn', modifier: 'left', location: [-99.131, 19.431] } },
              { name: 'Isabel la Católica', distance: 0, maneuver: { type: 'arrive', location: [-99.132, 19.432] } },
            ],
          },
        ],
      },
    ],
  };
  const r = Ruteo.parsear(json)[0];
  assert.equal(r.metros, 250);
  assert.deepEqual(
    r.pasos.map((p) => p.giro),
    ['SALIDA', 'IZQ', 'LLEGADA'],
  );
  assert.equal(r.pasos[1].calle, 'calle sin nombre');
  const codificada = Geo.codificarPolilinea(r.puntos);
  const decodificada = Geo.decodificarPolilinea(codificada);
  assert.equal(decodificada.length, r.puntos.length);
});

test('Snapshots OSM y rutas locales de combi en SLRC y CDMX', () => {
  const osm = new Osm();
  const cdmx = osm.lineasFijas(Ciudades.CDMX);
  assert.ok(cdmx.filter((l) => l.modo === 'METRO').length >= 20);
  assert.ok(cdmx.some((l) => l.modo === 'METROBUS'));

  const slrc = osm.lineasFijas(Ciudades.SLRC);
  assert.ok(slrc.length >= 8);
  const rutasSlrc = new Transporte(Ciudades.SLRC).planear(
    { lat: 32.4831, lon: -114.7809 },
    { lat: 32.4222, lon: -114.7433 },
    slrc,
  );
  const enCombi = rutasSlrc.find((r) => r.tramos.some((t) => t.modo === 'MICRO'))!;
  assert.ok(enCombi);
  assert.equal(enCombi.costo, 14.0);

  const jsonReloj = estadoRelojAJson({
    ciudad: 'San Luis Río Colorado',
    buscando: false,
    hoteles: [],
    orden: 'DISTANCIA',
    hotelElegido: null,
    rutas: rutasSlrc,
    nav: null,
    alerta: null,
    soloVibracion: false,
    mensaje: 'OK',
    mapa: null,
    generado: 123456,
  });
  assert.equal(jsonReloj.v, 1);
  assert.equal(jsonReloj.ciudad, 'San Luis Río Colorado');
});
