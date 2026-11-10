import { Geo } from './Geo.ts';

/*
 * Modelos compartidos entre el teléfono (Expo) y el reloj (Wear OS).
 * El formato JSON de `estadoRelojAJson` coincide exactamente con `EstadoReloj.deJson` en `core/Modelos.kt`.
 */

export interface Punto {
  lat: number;
  lon: number;
}

export type Giro =
  | 'SALIDA'
  | 'RECTO'
  | 'LIGERA_IZQ'
  | 'IZQ'
  | 'FUERTE_IZQ'
  | 'LIGERA_DER'
  | 'DER'
  | 'FUERTE_DER'
  | 'VUELTA_U'
  | 'ROTONDA'
  | 'LLEGADA';

export type Modo =
  | 'CAMINAR'
  | 'METRO'
  | 'TREN_LIGERO'
  | 'METROBUS'
  | 'TROLEBUS'
  | 'CABLEBUS'
  | 'BUS'
  | 'MICRO'
  | 'TREN'
  | 'TAXI';

export const ETIQUETA_MODO: Record<Modo, string> = {
  CAMINAR: 'Caminar',
  METRO: 'Metro',
  TREN_LIGERO: 'Tren ligero',
  METROBUS: 'Metrobús',
  TROLEBUS: 'Trolebús',
  CABLEBUS: 'Cablebús',
  BUS: 'Camión',
  MICRO: 'Micro / combi',
  TREN: 'Tren',
  TAXI: 'Taxi / app',
};

export type Orden = 'DISTANCIA' | 'PRECIO' | 'VALORACION';

export interface Hotel {
  id: string;
  nombre: string;
  tipo: string;
  punto: Punto;
  distanciaM: number;
  precioEstimado: number;
  estrellas: number;
  /** Calificación estimada 0–5 (estilo reseñas). */
  calificacion: number;
  telefono?: string | null;
  web?: string | null;
  direccion?: string | null;
}

export interface PasoNav {
  giro: Giro;
  calle: string;
  punto: Punto;
  distanciaM: number;
}

export interface Tramo {
  modo: Modo;
  linea: string;
  desde: string;
  hasta: string;
  minutos: number;
  costo: number;
  paradas?: number;
  desdePunto?: Punto | null;
  hastaPunto?: Punto | null;
  trazo?: Punto[];
}

export interface Ruta {
  titulo: string;
  tramos: Tramo[];
  minutos: number;
  costo: number;
  transbordos: number;
  estimada?: boolean;
}

export interface NavActual {
  destino: string;
  giro: Giro;
  calle: string;
  metrosAlGiro: number;
  flechaGrados: number;
  metrosRestantes: number;
  minutosRestantes: number;
  indicacion?: string | null;
  rumboObjetivo?: number;
  puntoGiro?: Punto | null;
  simulando?: boolean;
}

export type TipoAlerta = 'RIESGO' | 'QUIETO' | 'DESVIO' | 'INFO';

export interface Alerta {
  id: number;
  tipo: TipoAlerta;
  texto: string;
  pideRespuesta?: boolean;
}

export interface TrazoMapa {
  modo: Modo;
  puntos: Punto[];
}

export interface MapaReloj {
  yo: Punto | null;
  destino: Punto | null;
  destinoNombre: string;
  trazos: TrazoMapa[];
  rumbo?: number;
}

export interface EstadoReloj {
  ciudad: string;
  buscando: boolean;
  hoteles: Hotel[];
  orden: Orden;
  hotelElegido: string | null;
  rutas: Ruta[];
  nav: NavActual | null;
  alerta: Alerta | null;
  soloVibracion: boolean;
  mensaje: string | null;
  mapa: MapaReloj | null;
  generado: number;
}

export type TipoComando =
  | 'PEDIR_ESTADO'
  | 'BUSCAR_HOTELES'
  | 'ORDENAR'
  | 'ELEGIR_HOTEL'
  | 'ELEGIR_RUTA'
  | 'INICIAR_NAV'
  | 'DETENER_NAV'
  | 'RECALCULAR'
  | 'SIMULAR_NAV'
  | 'ESTOY_BIEN'
  | 'SOLO_VIBRACION';

export interface Comando {
  tipo: TipoComando;
  arg?: string | null;
}

/** Convierte un EstadoReloj al objeto JSON exacto que espera Wear OS (Modelos.kt). */
export function estadoRelojAJson(e: EstadoReloj): Record<string, unknown> {
  return {
    v: 1,
    ciudad: e.ciudad,
    buscando: e.buscando,
    hoteles: e.hoteles.map((h) => ({
      id: h.id,
      nombre: h.nombre,
      tipo: h.tipo,
      p: { lat: h.punto.lat, lon: h.punto.lon },
      dist: Math.round(h.distanciaM),
      precio: Math.round(h.precioEstimado),
      estrellas: Math.round(h.estrellas),
      calif: Math.round((h.calificacion ?? 0) * 10) / 10,
      tel: h.telefono ?? null,
      web: h.web ?? null,
      dir: h.direccion ?? null,
    })),
    orden: e.orden,
    hotelElegido: e.hotelElegido ?? null,
    rutas: e.rutas.map((r) => ({
      titulo: r.titulo,
      tramos: r.tramos.map((t) => {
        const obj: Record<string, unknown> = {
          modo: t.modo,
          linea: t.linea,
          desde: t.desde,
          hasta: t.hasta,
          min: Math.round(t.minutos),
          costo: t.costo,
          paradas: Math.round(t.paradas ?? 0),
          dp: t.desdePunto ? { lat: t.desdePunto.lat, lon: t.desdePunto.lon } : null,
          hp: t.hastaPunto ? { lat: t.hastaPunto.lat, lon: t.hastaPunto.lon } : null,
        };
        if (t.trazo && t.trazo.length >= 2) {
          obj.tz = Geo.codificarPolilinea(t.trazo);
        }
        return obj;
      }),
      min: Math.round(r.minutos),
      costo: r.costo,
      transbordos: Math.round(r.transbordos),
      estimada: r.estimada ?? true,
    })),
    nav: e.nav
      ? {
          destino: e.nav.destino,
          giro: e.nav.giro,
          calle: e.nav.calle,
          mGiro: Math.round(e.nav.metrosAlGiro),
          flecha: Math.round(e.nav.flechaGrados),
          mRest: Math.round(e.nav.metrosRestantes),
          minRest: Math.round(e.nav.minutosRestantes),
          ind: e.nav.indicacion ?? null,
          rumboObj: Math.round(e.nav.rumboObjetivo ?? 0),
          pGiro: e.nav.puntoGiro ? { lat: e.nav.puntoGiro.lat, lon: e.nav.puntoGiro.lon } : null,
          sim: Boolean(e.nav.simulando),
        }
      : null,
    alerta: e.alerta
      ? {
          id: Math.round(e.alerta.id),
          tipo: e.alerta.tipo,
          texto: e.alerta.texto,
          resp: Boolean(e.alerta.pideRespuesta),
        }
      : null,
    soloVibracion: e.soloVibracion,
    mensaje: e.mensaje ?? null,
    mapa: e.mapa
      ? {
          yo: e.mapa.yo ? { lat: e.mapa.yo.lat, lon: e.mapa.yo.lon } : null,
          dest: e.mapa.destino ? { lat: e.mapa.destino.lat, lon: e.mapa.destino.lon } : null,
          destN: e.mapa.destinoNombre,
          rumbo: Math.round(e.mapa.rumbo ?? 0),
          tr: e.mapa.trazos.map((tr) => ({
            m: tr.modo,
            p: Geo.codificarPolilinea(tr.puntos),
          })),
        }
      : null,
    generado: Math.round(e.generado),
  };
}
