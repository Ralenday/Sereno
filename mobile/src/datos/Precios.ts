import { Geo } from '../core/Geo.ts';
import type { Punto } from '../core/Modelos.ts';
import type { Ciudad } from './Ciudades.ts';

/**
 * Estimador de precio por noche, estrellas y calificación de alojamientos.
 *
 * OpenStreetMap no trae precios ni reseñas y casi nunca trae `stars`, así que sin esto todos los
 * hoteles del mismo tipo salían con el mismo precio y 0★. Aquí se simula de forma **determinista**
 * (mismo hotel => mismos valores en cada búsqueda) combinando:
 *  - `stars` de OSM cuando existe;
 *  - la cadena/marca reconocida en el nombre o en `brand`/`operator` (Fiesta Inn, City Express, Hilton…);
 *  - palabras del nombre ("hostal", "suites", "auto hotel", "boutique"…);
 *  - zonas caras/baratas de cada ciudad (Polanco, Reforma, Condesa… / periferia);
 *  - una variación pseudoaleatoria estable derivada del id de OSM.
 */

export interface EstimacionHotel {
  precio: number;
  estrellas: number;
  calificacion: number;
}

interface Cadena {
  patron: RegExp;
  estrellas: number;
  /** Multiplicador sobre el precio base de "hotel" de la ciudad. */
  factor: number;
}

// El orden importa: el primer patrón que coincide gana (p. ej. "Holiday Inn Express" antes que "Holiday Inn").
const CADENAS: Cadena[] = [
  // Lujo
  { patron: /ritz[- ]carlton|four seasons|st\.? regis|waldorf|kempinski|rosewood|banyan tree|las alcobas/, estrellas: 5, factor: 4.2 },
  { patron: /\bjw\b|marriott reforma|sofitel|presidente|intercontinental|hyatt|andaz|\bw mexico|condesa df|live aqua|grand fiesta/, estrellas: 5, factor: 3.2 },
  { patron: /camino real|hilton|westin|sheraton|marquis|galeria plaza|nizuc|krystal grand|le meridien/, estrellas: 5, factor: 2.7 },
  // Superior
  { patron: /fiesta americana|crowne plaza|courtyard|marriott|radisson|barcel[oó]|novotel|aloft|\bac hotel|nh\b|nh collection|wyndham|four points|staybridge|embassy suites/, estrellas: 4, factor: 1.9 },
  { patron: /holiday inn express|hampton inn|homewood|fairfield|best western plus|krystal|araiza|misi[oó]n|cocopah|casino/, estrellas: 4, factor: 1.45 },
  { patron: /holiday inn/, estrellas: 4, factor: 1.6 },
  // Medio
  { patron: /fiesta inn|city express plus|gamma|comfort inn|la quinta|best western|garden plaza|real inn/, estrellas: 3, factor: 1.15 },
  { patron: /city express|city junior|city centro|\bone\b|ibis|microtel|hotel one|smart|flowsuites|selina/, estrellas: 3, factor: 0.9 },
  // Económico
  { patron: /hotel de paso|auto ?hotel|autohotel|love/, estrellas: 2, factor: 0.55 },
  { patron: /mundo joven|hostel|hostal|backpacker|albergue/, estrellas: 1, factor: 0.32 },
  { patron: /posada|casa de hu[eé]spedes|mes[oó]n|pensi[oó]n/, estrellas: 2, factor: 0.6 },
];

/** Ajustes por palabra suelta en el nombre (se multiplican entre sí). */
const PALABRAS: Array<[RegExp, number]> = [
  [/boutique|spa|resort|grand|gran hotel|palacio|hacienda/, 1.3],
  [/suites?\b/, 1.12],
  [/plaza|real|royal|imperial/, 1.06],
  [/econ[oó]mic|express|inn\b/, 0.92],
  [/sin nombre/, 0.8],
];

export interface ZonaPrecio {
  nombre: string;
  centro: Punto;
  radioM: number;
  factor: number;
}

/** FNV-1a de 32 bits: hash estable para que cada hotel tenga siempre la misma variación. */
export function hashTexto(s: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

/** Número estable en [0, 1) a partir de un texto y una "sal" (para obtener valores independientes). */
function azar(id: string, sal: string): number {
  return hashTexto(`${sal}:${id}`) / 4294967296;
}

function limitar(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

function redondearPrecio(v: number): number {
  if (v < 600) return Math.max(150, Math.round(v / 10) * 10);
  if (v < 2000) return Math.round(v / 50) * 50;
  return Math.round(v / 100) * 100;
}

function factorZona(ciudad: Ciudad, p: Punto): number {
  const zonas = ciudad.zonasPrecio ?? [];
  let mejor = 1.0;
  let enZona = false;
  for (const z of zonas) {
    const d = Geo.distancia(z.centro, p);
    if (d <= z.radioM) {
      // Más caro en el corazón de la zona, se diluye hacia el borde
      const f = 1 + (z.factor - 1) * (1 - 0.5 * (d / z.radioM));
      if (!enZona || Math.abs(f - 1) > Math.abs(mejor - 1)) mejor = f;
      enZona = true;
    }
  }
  if (enZona) return mejor;
  // Periferia: lejos del centro suele ser más barato
  const km = Geo.distancia(ciudad.centro, p) / 1000;
  const umbral = Math.max(3, ciudad.radioKm * 0.25);
  if (km > umbral) return limitar(1 - 0.03 * (km - umbral), 0.75, 1);
  return 1.0;
}

function estrellasDeTags(tags: Record<string, unknown>): number {
  const n = parseInt(String(tags.stars ?? '').slice(0, 1), 10);
  return Number.isInteger(n) && n >= 1 && n <= 5 ? n : 0;
}

export const Precios = {
  estimar(
    ciudad: Ciudad,
    id: string,
    tipo: string,
    nombre: string,
    punto: Punto,
    tags: Record<string, unknown> = {},
  ): EstimacionHotel {
    const texto = `${nombre} ${tags.brand ?? ''} ${tags.operator ?? ''}`.toLowerCase();
    const base = ciudad.preciosNoche[tipo] ?? ciudad.preciosNoche.hotel;
    const baseHotel = ciudad.preciosNoche.hotel;

    const cadena = CADENAS.find((c) => c.patron.test(texto));
    const estrellasOsm = estrellasDeTags(tags);

    // 1) Estrellas: OSM > cadena > estimación por tipo
    let estrellas = estrellasOsm;
    if (!estrellas && cadena) estrellas = cadena.estrellas;
    if (!estrellas) {
      const r = azar(id, 'estrellas');
      switch (tipo) {
        case 'hostel':
          estrellas = r < 0.7 ? 1 : 2;
          break;
        case 'motel':
          estrellas = r < 0.75 ? 2 : 3;
          break;
        case 'guest_house':
          estrellas = r < 0.5 ? 2 : 3;
          break;
        case 'apartment':
          estrellas = r < 0.6 ? 3 : 4;
          break;
        default:
          estrellas = r < 0.3 ? 2 : r < 0.8 ? 3 : 4;
      }
    }

    // 2) Precio
    let precio: number;
    if (cadena) {
      precio = baseHotel * cadena.factor;
    } else {
      precio = base * (estrellasOsm ? limitar(1 + 0.35 * (estrellasOsm - 2), 0.6, 3.5) : 1 + 0.18 * (estrellas - 2.5));
    }
    for (const [re, f] of PALABRAS) if (re.test(texto)) precio *= f;
    precio *= factorZona(ciudad, punto);
    // Variación individual estable de ±22 %
    precio *= 0.78 + 0.44 * azar(id, 'precio');

    // 3) Calificación tipo reseñas (escala 0–5): mejor con más estrellas, con variación propia
    const ruido = (azar(id, 'calif') + azar(id, 'calif2') - 1) * 0.9; // ~ -0.9 .. +0.9, centrado
    const calificacion = limitar(3.35 + 0.28 * estrellas + ruido, 2.4, 4.9);

    return {
      precio: redondearPrecio(precio),
      estrellas,
      calificacion: Math.round(calificacion * 10) / 10,
    };
  },
};
