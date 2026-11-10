import { Geo } from '../core/Geo.ts';
import type { Punto } from '../core/Modelos.ts';
import type { Ciudad } from './Ciudades.ts';
import type { Osm } from './Osm.ts';

export type NivelRiesgo = 'BAJO' | 'MEDIO' | 'ALTO' | 'DESCONOCIDO';

export interface EvaluacionRiesgo {
  nivel: NivelRiesgo;
  motivo: string;
}

export const RECURSO_FGJ = '41e05057-26ad-4670-9346-cade8ddbe3fe';
export const ANIO_DATOS = 2024;
const RADIO_M = 300.0;

export class Riesgo {
  private readonly osm: Osm;
  private puntosPorAlcaldia = new Map<string, Array<{ punto: Punto; hora: number }>>();
  private alcaldiaPorCelda = new Map<string, string | null>();
  private reportesPropios: Punto[] = [];

  constructor(osm: Osm) {
    this.osm = osm;
  }

  async evaluar(ciudad: Ciudad, p: Punto, ahora = new Date()): Promise<EvaluacionRiesgo> {
    const hora = ahora.getHours();
    const noche = hora >= 20 || hora < 6;
    if (this.reportesPropios.some((r) => Geo.distancia(r, p) < 200)) {
      return { nivel: 'ALTO', motivo: 'Marcaste esta zona como insegura' };
    }
    if (ciudad.fuenteRiesgo === 'CDMX_FGJ') {
      return this.evaluarCdmx(p, noche);
    }
    return {
      nivel: noche ? 'MEDIO' : 'DESCONOCIDO',
      motivo: noche ? 'De noche: prefiere calles principales' : 'Sin datos abiertos de delitos en esta ciudad',
    };
  }

  private async evaluarCdmx(p: Punto, noche: boolean): Promise<EvaluacionRiesgo> {
    const alcaldia = await this.alcaldiaDe(p);
    if (!alcaldia) {
      return { nivel: 'DESCONOCIDO', motivo: 'No se pudo ubicar la alcaldía' };
    }
    let puntos: Array<{ punto: Punto; hora: number }>;
    try {
      puntos = await this.robosEn(alcaldia);
    } catch {
      return { nivel: 'DESCONOCIDO', motivo: 'Datos de la FGJ no disponibles' };
    }
    const cerca = puntos.filter((it) => Geo.distancia(it.punto, p) <= RADIO_M);
    const deNoche = cerca.filter((it) => it.hora >= 20 || it.hora < 6).length;
    const peso = cerca.length + (noche ? deNoche : 0);
    let nivel: NivelRiesgo = 'BAJO';
    if (peso >= 15) nivel = 'ALTO';
    else if (peso >= 6) nivel = 'MEDIO';
    return {
      nivel,
      motivo: `${cerca.length} robos a transeúnte en 300 m (${ANIO_DATOS}, FGJ)`,
    };
  }

  private async alcaldiaDe(p: Punto): Promise<string | null> {
    const celda = `${p.lat.toFixed(2)},${p.lon.toFixed(2)}`;
    if (this.alcaldiaPorCelda.has(celda)) {
      return this.alcaldiaPorCelda.get(celda) ?? null;
    }
    try {
      const a = await this.osm.alcaldia(p);
      this.alcaldiaPorCelda.set(celda, a);
      return a;
    } catch {
      this.alcaldiaPorCelda.set(celda, null);
      return null;
    }
  }

  private async robosEn(alcaldia: string): Promise<Array<{ punto: Punto; hora: number }>> {
    const prev = this.puntosPorAlcaldia.get(alcaldia);
    if (prev) return prev;
    const filtro = encodeURIComponent(JSON.stringify({ alcaldia_catalogo: alcaldia }));
    const url = `https://datos.cdmx.gob.mx/api/3/action/datastore_search?resource_id=${RECURSO_FGJ}&q=TRANSEUNTE&limit=5000&fields=latitud,longitud,hora_hecho&filters=${filtro}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const json = await res.json();
    const records: any[] = json?.result?.records ?? [];
    const lista: Array<{ punto: Punto; hora: number }> = [];
    for (const r of records) {
      const lat = parseFloat(String(r.latitud ?? ''));
      const lon = parseFloat(String(r.longitud ?? ''));
      if (Number.isNaN(lat) || Number.isNaN(lon)) continue;
      const h = parseInt(String(r.hora_hecho ?? '').slice(0, 2), 10);
      lista.push({
        punto: { lat, lon },
        hora: Number.isNaN(h) ? 12 : h,
      });
    }
    this.puntosPorAlcaldia.set(alcaldia, lista);
    return lista;
  }

  reportar(p: Punto): void {
    this.reportesPropios.push(p);
  }

  reportes(): Punto[] {
    return this.reportesPropios;
  }
}
