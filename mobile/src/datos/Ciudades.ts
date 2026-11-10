import { Geo } from '../core/Geo.ts';
import type { Modo, Punto } from '../core/Modelos.ts';
import type { ZonaPrecio } from './Precios.ts';

export type FuenteRiesgo = 'CDMX_FGJ' | 'SOLO_LOCAL';

export interface Ciudad {
  id: string;
  nombre: string;
  tamano: string;
  centro: Punto;
  radioKm: number;
  tarifas: Partial<Record<Modo, number>>;
  tarifaDescuento?: number;
  tarifasVerificadas: boolean;
  taxiBanderazo: number;
  taxiPorKm: number;
  taxiPorZona?: boolean;
  preciosNoche: Record<string, number>;
  /** Zonas donde el hospedaje es más caro (o barato) que el promedio de la ciudad. */
  zonasPrecio?: ZonaPrecio[];
  fuenteRiesgo: FuenteRiesgo;
  transporteEnOsm: string;
}

const SLRC: Ciudad = {
  id: 'slrc',
  nombre: 'San Luis Río Colorado',
  tamano: 'Chica',
  centro: { lat: 32.4840, lon: -114.7790 },
  radioKm: 15.0,
  tarifas: { BUS: 14.0, MICRO: 14.0 },
  tarifaDescuento: 7.0,
  tarifasVerificadas: true,
  taxiBanderazo: 45.0,
  taxiPorKm: 0.0,
  taxiPorZona: true,
  preciosNoche: {
    hotel: 950,
    motel: 650,
    guest_house: 750,
    hostel: 450,
    apartment: 900,
  },
  zonasPrecio: [
    { nombre: 'Av. Obregón / centro', centro: { lat: 32.4840, lon: -114.7790 }, radioM: 900, factor: 1.12 },
    { nombre: 'Garita / frontera', centro: { lat: 32.4930, lon: -114.7900 }, radioM: 900, factor: 1.08 },
  ],
  fuenteRiesgo: 'SOLO_LOCAL',
  transporteEnOsm:
    'Local: 4 rutas de combi mapeadas en assets/datos/rutas_locales_slrc.json ($14 general / $7 est. y mayores)',
};

const CDMX: Ciudad = {
  id: 'cdmx',
  nombre: 'Ciudad de México',
  tamano: 'Grande',
  centro: { lat: 19.4326, lon: -99.1332 },
  radioKm: 40.0,
  tarifas: {
    METRO: 5.0,
    METROBUS: 6.0,
    TROLEBUS: 4.0,
    TREN_LIGERO: 3.0,
    CABLEBUS: 7.0,
    BUS: 2.0,
    MICRO: 7.5,
    TREN: 13.0,
  },
  tarifasVerificadas: true,
  taxiBanderazo: 35.0,
  taxiPorKm: 9.0,
  taxiPorZona: false,
  preciosNoche: {
    hotel: 1600,
    motel: 750,
    guest_house: 950,
    hostel: 420,
    apartment: 1300,
  },
  zonasPrecio: [
    { nombre: 'Polanco', centro: { lat: 19.4330, lon: -99.1960 }, radioM: 1800, factor: 1.55 },
    { nombre: 'Reforma / Juárez', centro: { lat: 19.4285, lon: -99.1620 }, radioM: 1500, factor: 1.4 },
    { nombre: 'Condesa / Roma', centro: { lat: 19.4150, lon: -99.1690 }, radioM: 1700, factor: 1.25 },
    { nombre: 'Santa Fe', centro: { lat: 19.3600, lon: -99.2600 }, radioM: 2500, factor: 1.35 },
    { nombre: 'Coyoacán', centro: { lat: 19.3500, lon: -99.1620 }, radioM: 1500, factor: 1.1 },
    { nombre: 'Aeropuerto', centro: { lat: 19.4361, lon: -99.0719 }, radioM: 2200, factor: 1.15 },
    { nombre: 'Centro Histórico', centro: { lat: 19.4330, lon: -99.1350 }, radioM: 1200, factor: 0.95 },
    { nombre: 'Tlalpan / Viaducto (moteles)', centro: { lat: 19.3950, lon: -99.1420 }, radioM: 1500, factor: 0.85 },
  ],
  fuenteRiesgo: 'CDMX_FGJ',
  transporteEnOsm: 'Bueno (Metro, Metrobús, Trolebús, Tren Ligero, Cablebús mapeados)',
};

function zonaSlrc(centro: Punto, p: Punto): number {
  const km = Geo.distancia(centro, p) / 1000.0;
  if (km <= 1.8) return 0; // Zona Centro / Comercial ($45)
  if (km <= 3.5) return 1; // Zona intermedia ($60)
  if (km <= 5.5) return 2; // Mezquite, Progreso, IMSS ($75)
  return 3; // Parque Industrial, 5 de Mayo Sur ($90)
}

function escalonPorMetros(metros: number): number {
  if (metros <= 2_200) return 0;
  if (metros <= 4_200) return 1;
  if (metros <= 6_500) return 2;
  return 3;
}

export const Ciudades = {
  SLRC,
  CDMX,
  todas: [SLRC, CDMX] as Ciudad[],

  detectar(p: Punto): Ciudad | null {
    let mejor: Ciudad | null = null;
    let mejorKm = Infinity;
    for (const c of Ciudades.todas) {
      const km = Geo.distancia(p, c.centro) / 1000.0;
      if (km <= c.radioKm && km < mejorKm) {
        mejor = c;
        mejorKm = km;
      }
    }
    return mejor;
  },

  /**
   * Devuelve la ciudad soportada si estás dentro de su radio, o crea una ciudad dinámica centrada
   * en tu ubicación GPS real para poder buscar hoteles y rutas donde sea que estés.
   */
  paraGps(p: Punto, nombreDetectado?: string | null): Ciudad {
    const conocida = Ciudades.detectar(p);
    if (conocida) return conocida;
    return {
      id: 'gps_actual',
      nombre: nombreDetectado?.trim() || 'Tu ubicación GPS',
      tamano: 'GPS actual',
      centro: p,
      radioKm: 25.0,
      tarifas: { BUS: 12.0, MICRO: 12.0, METRO: 5.0 },
      tarifasVerificadas: false,
      taxiBanderazo: 40.0,
      taxiPorKm: 10.0,
      taxiPorZona: false,
      preciosNoche: {
        hotel: 1100,
        motel: 700,
        guest_house: 800,
        hostel: 450,
        apartment: 950,
      },
      fuenteRiesgo: 'SOLO_LOCAL',
      transporteEnOsm: 'Consulta en vivo en OpenStreetMap alrededor de tu GPS',
    };
  },

  porId(id?: string | null): Ciudad | null {
    return Ciudades.todas.find((c) => c.id === id) ?? null;
  },

  precioEstimado(c: Ciudad, tipo: string, estrellas: number): number {
    const base = c.preciosNoche[tipo] ?? c.preciosNoche.hotel;
    const factor = estrellas > 0 ? Math.max(0.6, 1.0 + 0.35 * (estrellas - 2)) : 1.0;
    return Math.trunc((base * factor) / 50) * 50;
  },

  costoTaxi(c: Ciudad, metros: number, origen?: Punto | null, destino?: Punto | null): number {
    if (!c.taxiPorZona) {
      return c.taxiBanderazo + (c.taxiPorKm * metros) / 1000.0;
    }
    const zOrigen = origen ? zonaSlrc(c.centro, origen) : 0;
    const zDestino = destino ? zonaSlrc(c.centro, destino) : escalonPorMetros(metros);
    const saltoZonas = Math.abs(zOrigen - zDestino);
    const zonaMax = Math.max(zOrigen, zDestino);
    return c.taxiBanderazo + 15.0 * Math.max(zonaMax, saltoZonas);
  },
};
