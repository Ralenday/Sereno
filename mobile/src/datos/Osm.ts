import { Geo } from '../core/Geo.ts';
import type { Hotel, Modo, Punto } from '../core/Modelos.ts';
import { type Ciudad, Ciudades } from './Ciudades.ts';

import hotelesCdmx from '../../assets/datos/hoteles_cdmx.json' with { type: 'json' };
import hotelesSlrc from '../../assets/datos/hoteles_slrc.json' with { type: 'json' };
import lineasCdmx from '../../assets/datos/lineas_cdmx.json' with { type: 'json' };
import lineasSlrc from '../../assets/datos/lineas_slrc.json' with { type: 'json' };
import rutasLocalesCdmx from '../../assets/datos/rutas_locales_cdmx.json' with { type: 'json' };
import rutasLocalesSlrc from '../../assets/datos/rutas_locales_slrc.json' with { type: 'json' };

export interface Parada {
  id: number;
  nombre: string;
  punto: Punto;
}

export interface Linea {
  id: number;
  ref: string;
  nombre: string;
  modo: Modo;
  red: string;
  paradas: Parada[];
}

const SERVIDORES_OVERPASS = [
  'https://overpass-api.de/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
  'https://overpass.private.coffee/api/interpreter',
];

export const NOMBRE_TIPO: Record<string, string> = {
  hotel: 'Hotel',
  motel: 'Motel',
  hostel: 'Hostal',
  guest_house: 'Casa de huéspedes',
  apartment: 'Departamento',
};

const COPIAS_HOTELES: Record<string, any> = {
  slrc: hotelesSlrc,
  cdmx: hotelesCdmx,
};

const COPIAS_LINEAS: Record<string, any> = {
  slrc: lineasSlrc,
  cdmx: lineasCdmx,
};

const COPIAS_RUTAS_LOCALES: Record<string, any> = {
  slrc: rutasLocalesSlrc,
  cdmx: rutasLocalesCdmx,
};

function modoDe(route: string, red: string): Modo {
  const r = red.toLowerCase();
  switch (route) {
    case 'subway':
    case 'monorail':
      return 'METRO';
    case 'light_rail':
    case 'tram':
      return 'TREN_LIGERO';
    case 'train':
      return 'TREN';
    case 'aerialway':
      return 'CABLEBUS';
    case 'trolleybus':
      return 'TROLEBUS';
    case 'minibus':
    case 'share_taxi':
      return 'MICRO';
    default:
      if (r.includes('metrob')) return 'METROBUS';
      if (r.includes('trolebús') || r.includes('trolebus')) return 'TROLEBUS';
      return 'BUS';
  }
}

export class Osm {
  private cacheLineasFijas = new Map<string, Linea[]>();

  async overpass(consulta: string): Promise<any> {
    let ultimoError: unknown = null;
    for (const s of SERVIDORES_OVERPASS) {
      try {
        const res = await fetch(s, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'User-Agent': 'Sereno/0.1 (proyecto escolar; Expo)',
          },
          body: `data=${encodeURIComponent(consulta)}`,
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return await res.json();
      } catch (e) {
        ultimoError = e;
      }
    }
    throw ultimoError ?? new Error('Overpass no disponible');
  }

  /**
   * Busca alojamiento en `radioM` alrededor de `centro`.
   * Si estás en una ciudad con copia local (SLRC/CDMX) la usa al instante; si estás usando tu GPS
   * en otra zona o la copia local no cubre tu coordenada actual, consulta Overpass en vivo.
   */
  async hoteles(ciudad: Ciudad, centro: Punto, radioM: number): Promise<Hotel[]> {
    const local = COPIAS_HOTELES[ciudad.id];
    let enCopia: Hotel[] = [];
    if (local) {
      enCopia = this.parsearHoteles(local, ciudad, centro).filter((h) => h.distanciaM <= radioM);
      if (enCopia.length >= 2) return enCopia;
    }
    try {
      const vivo = await this.overpass(
        `[out:json][timeout:35];nwr["tourism"~"^(hotel|motel|hostel|guest_house|apartment)$"](around:${radioM},${centro.lat},${centro.lon});out center tags 150;`,
      );
      const enVivo = this.parsearHoteles(vivo, ciudad, centro).filter((h) => h.distanciaM <= radioM);
      if (enVivo.length > 0) return enVivo;
    } catch {
      // Si Overpass falla sin conexión, devolver lo que haya en copia local
    }
    return enCopia;
  }

  parsearHoteles(json: any, ciudad: Ciudad, centro: Punto): Hotel[] {
    const elementos: any[] = json?.elements ?? [];
    const res: Hotel[] = [];
    for (const e of elementos) {
      const tags = e?.tags;
      if (!tags) continue;
      let p: Punto | null = null;
      if (typeof e.lat === 'number' && typeof e.lon === 'number') {
        p = { lat: e.lat, lon: e.lon };
      } else if (e.center && typeof e.center.lat === 'number' && typeof e.center.lon === 'number') {
        p = { lat: e.center.lat, lon: e.center.lon };
      }
      if (!p) continue;
      const tipo = String(tags.tourism ?? 'hotel');
      const estrellasStr = String(tags.stars ?? '').slice(0, 1);
      const estrellas = Number.isInteger(parseInt(estrellasStr, 10)) ? parseInt(estrellasStr, 10) : 0;
      const nombre =
        typeof tags.name === 'string' && tags.name.trim() !== ''
          ? tags.name.trim()
          : `${NOMBRE_TIPO[tipo] ?? 'Hotel'} sin nombre`;
      const calle = typeof tags['addr:street'] === 'string' ? tags['addr:street'].trim() : '';
      const num = typeof tags['addr:housenumber'] === 'string' ? tags['addr:housenumber'].trim() : '';
      const tel = (tags.phone || tags['contact:phone'] || '').trim() || null;
      const web = (tags.website || tags['contact:website'] || '').trim() || null;

      res.push({
        id: `${e.type}/${e.id}`,
        nombre,
        tipo,
        punto: p,
        distanciaM: Math.round(Geo.distancia(centro, p)),
        precioEstimado: Ciudades.precioEstimado(ciudad, tipo, estrellas),
        estrellas,
        telefono: tel,
        web,
        direccion: calle ? `${calle} ${num}`.trim() : null,
      });
    }
    return res;
  }

  lineasFijas(ciudad: Ciudad): Linea[] {
    const enCache = this.cacheLineasFijas.get(ciudad.id);
    if (enCache) return enCache;
    const osm = COPIAS_LINEAS[ciudad.id] ? this.parsearLineas(COPIAS_LINEAS[ciudad.id]) : [];
    const locales = COPIAS_RUTAS_LOCALES[ciudad.id]
      ? this.parsearRutasLocales(COPIAS_RUTAS_LOCALES[ciudad.id])
      : [];
    const total = [...osm, ...locales];
    this.cacheLineasFijas.set(ciudad.id, total);
    return total;
  }

  parsearRutasLocales(json: any): Linea[] {
    const rutas: any[] = (json?.rutas ?? []).filter((r: any) => r?.activa !== false);
    return rutas
      .map((r: any, i: number) => {
        const paradas: Parada[] = (r.paradas ?? []).map((p: any, j: number) => ({
          id: -1000 * (i + 1) - j,
          nombre: String(p.nombre ?? 'Parada'),
          punto: { lat: Number(p.lat), lon: Number(p.lon) },
        }));
        return {
          id: -1 - i,
          ref: String(r.ref ?? ''),
          nombre: String(r.nombre ?? ''),
          modo: (r.modo ?? 'BUS') as Modo,
          red: 'local',
          paradas,
        };
      })
      .filter((l: Linea) => l.paradas.length >= 2);
  }

  parsearLineas(json: any): Linea[] {
    const elementos: any[] = json?.elements ?? [];
    const nodos = new Map<number, any>();
    for (const e of elementos) {
      if (e?.type === 'node') nodos.set(Number(e.id), e);
    }
    const lineas: Linea[] = [];
    for (const r of elementos) {
      if (r?.type !== 'relation' || !r.tags) continue;
      const t = r.tags;
      const miembros: any[] = (r.members ?? []).filter((m: any) => m?.type === 'node');
      const conRolParada = miembros.filter((m: any) => String(m.role ?? '').startsWith('stop'));
      const elegidos =
        conRolParada.length > 0
          ? conRolParada
          : miembros.filter((m: any) => {
              const rol = String(m.role ?? '');
              return rol === '' || rol.startsWith('platform');
            });
      const paradas: Parada[] = [];
      const vistos = new Set<number>();
      for (const m of elegidos) {
        const refId = Number(m.ref);
        if (vistos.has(refId)) continue;
        const n = nodos.get(refId);
        if (!n) continue;
        vistos.add(refId);
        const nombre = String(n.tags?.name ?? '').trim() || 'Parada';
        paradas.push({
          id: refId,
          nombre,
          punto: { lat: Number(n.lat), lon: Number(n.lon) },
        });
      }
      if (paradas.length < 2) continue;
      const red = String(t.network ?? '');
      const ref = String(t.ref ?? '');
      lineas.push({
        id: Number(r.id),
        ref,
        nombre: String(t.name ?? '').trim() || ref,
        modo: modoDe(String(t.route ?? ''), red),
        red,
        paradas,
      });
    }
    return lineas;
  }

  async lineasDeCamion(origen: Punto, destino: Punto, radioM = 700): Promise<Linea[]> {
    const modos = '^(bus|trolleybus|minibus|share_taxi|subway|light_rail|tram)$';
    const q = `[out:json][timeout:45];node(around:${radioM},${origen.lat},${origen.lon})->.a;rel(bn.a)[type=route][route~"${modos}"]->.ra;node(around:${radioM},${destino.lat},${destino.lon})->.b;rel(bn.b)[type=route][route~"${modos}"]->.rb;rel.ra.rb->.r;.r out body;node(r.r);out body;`;
    const json = await this.overpass(q);
    return this.parsearLineas(json);
  }

  async buscarLugar(texto: string, ciudad: Ciudad): Promise<{ nombre: string; punto: Punto } | null> {
    const d = 0.3;
    const c = ciudad.centro;
    const url = `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&bounded=1&viewbox=${c.lon - d},${c.lat + d},${c.lon + d},${c.lat - d}&q=${encodeURIComponent(texto)}`;
    const res = await fetch(url, {
      headers: {
        'User-Agent': 'Sereno/0.1 (proyecto escolar; Expo)',
        'Accept-Language': 'es-MX,es',
      },
    });
    if (!res.ok) return null;
    const arr = await res.json();
    if (!Array.isArray(arr) || arr.length === 0) return null;
    const r = arr[0];
    return {
      nombre: String(r.display_name ?? '').split(',')[0],
      punto: { lat: Number(r.lat), lon: Number(r.lon) },
    };
  }

  async nombreLocalidad(p: Punto): Promise<string | null> {
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=12&lat=${p.lat}&lon=${p.lon}`;
      const res = await fetch(url, {
        headers: {
          'User-Agent': 'Sereno/0.1 (proyecto escolar; Expo)',
          'Accept-Language': 'es-MX,es',
        },
      });
      if (!res.ok) return null;
      const json = await res.json();
      const dir = json?.address;
      if (!dir) return null;
      return (
        dir.city ||
        dir.town ||
        dir.municipality ||
        dir.village ||
        dir.county ||
        ''
      ).trim() || null;
    } catch {
      return null;
    }
  }

  async alcaldia(p: Punto): Promise<string | null> {
    const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=${p.lat}&lon=${p.lon}`;
    const res = await fetch(url, {
      headers: {
        'User-Agent': 'Sereno/0.1 (proyecto escolar; Expo)',
        'Accept-Language': 'es-MX,es',
      },
    });
    if (!res.ok) return null;
    const json = await res.json();
    const dir = json?.address;
    if (!dir) return null;
    return (dir.borough || dir.city_district || '').trim() || null;
  }
}
