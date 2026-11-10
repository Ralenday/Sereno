import { Geo } from '../core/Geo.ts';
import type { Giro, PasoNav, Punto } from '../core/Modelos.ts';

export interface RutaPie {
  metros: number;
  segundos: number;
  puntos: Punto[];
  pasos: PasoNav[];
}

const SERVIDOR = 'https://routing.openstreetmap.de/routed-foot/route/v1/driving/';

export const Ruteo = {
  async caminar(origen: Punto, destino: Punto, alternativas = false): Promise<RutaPie[]> {
    const coords = `${origen.lon.toFixed(6)},${origen.lat.toFixed(6)};${destino.lon.toFixed(6)},${destino.lat.toFixed(6)}`;
    const url = `${SERVIDOR}${coords}?overview=full&geometries=polyline&steps=true&alternatives=${alternativas}`;
    const res = await fetch(url, {
      headers: {
        'User-Agent': 'Sereno/0.1 (proyecto escolar; Expo)',
        'Accept-Language': 'es-MX,es',
      },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} en OSRM`);
    const json = await res.json();
    return Ruteo.parsear(json);
  },

  parsear(json: any): RutaPie[] {
    if (json?.code !== 'Ok') {
      throw new Error(`OSRM: ${json?.message ?? json?.code ?? 'Error'}`);
    }
    return (json.routes ?? []).map((r: any) => {
      const pasos: PasoNav[] = (r.legs ?? [])
        .flatMap((leg: any) => leg.steps ?? [])
        .map((s: any) => {
          const m = s.maneuver ?? {};
          const loc = m.location ?? [0, 0];
          const nombre = typeof s.name === 'string' && s.name.trim() !== '' ? s.name : 'calle sin nombre';
          return {
            giro: Ruteo.giroDe(m.type ?? '', m.modifier ?? ''),
            calle: nombre,
            punto: { lat: Number(loc[1]), lon: Number(loc[0]) },
            distanciaM: Math.round(Number(s.distance ?? 0)),
          };
        });
      return {
        metros: Math.round(Number(r.distance ?? 0)),
        segundos: Math.round(Number(r.duration ?? 0)),
        puntos: Geo.decodificarPolilinea(String(r.geometry ?? '')),
        pasos,
      };
    });
  },

  giroDe(tipo: string, modificador: string): Giro {
    if (tipo === 'depart') return 'SALIDA';
    if (tipo === 'arrive') return 'LLEGADA';
    if (
      tipo === 'roundabout' ||
      tipo === 'rotary' ||
      tipo === 'roundabout turn' ||
      tipo === 'exit roundabout' ||
      tipo === 'exit rotary'
    ) {
      return 'ROTONDA';
    }
    switch (modificador) {
      case 'slight left':
        return 'LIGERA_IZQ';
      case 'left':
        return 'IZQ';
      case 'sharp left':
        return 'FUERTE_IZQ';
      case 'slight right':
        return 'LIGERA_DER';
      case 'right':
        return 'DER';
      case 'sharp right':
        return 'FUERTE_DER';
      case 'uturn':
        return 'VUELTA_U';
      default:
        return 'RECTO';
    }
  },
};
