import { Geo } from '../core/Geo.ts';
import type { Modo, Punto } from '../core/Modelos.ts';

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
