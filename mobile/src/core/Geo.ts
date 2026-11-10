import type { Punto } from './Modelos.ts';

const RADIO_TIERRA_M = 6_371_000.0;

const aRad = (grados: number): number => (grados * Math.PI) / 180.0;
const aGrad = (rad: number): number => (rad * 180.0) / Math.PI;

export const Geo = {
  /** Distancia en metros entre dos puntos (fórmula de haversine). */
  distancia(a: Punto, b: Punto): number {
    const dLat = aRad(b.lat - a.lat);
    const dLon = aRad(b.lon - a.lon);
    const sinLat = Math.sin(dLat / 2);
    const sinLon = Math.sin(dLon / 2);
    const h =
      sinLat * sinLat +
      Math.cos(aRad(a.lat)) * Math.cos(aRad(b.lat)) * sinLon * sinLon;
    return 2 * RADIO_TIERRA_M * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  },

  /** Rumbo de a hacia b en grados (0 = norte, 90 = este). */
  rumbo(a: Punto, b: Punto): number {
    const lat1 = aRad(a.lat);
    const lat2 = aRad(b.lat);
    const dLon = aRad(b.lon - a.lon);
    const y = Math.sin(dLon) * Math.cos(lat2);
    const x =
      Math.cos(lat1) * Math.sin(lat2) -
      Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
    return (aGrad(Math.atan2(y, x)) + 360) % 360;
  },

  /** Diferencia de ángulos normalizada a -180..180. */
  diferenciaAngulo(destino: number, actual: number): number {
    let d = (destino - actual) % 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    return Math.round(d);
  },

  /** Distancia mínima (m) de un punto a una polilínea. */
  distanciaALinea(p: Punto, linea: Punto[]): number {
    if (linea.length === 0) return Number.MAX_VALUE;
    if (linea.length === 1) return Geo.distancia(p, linea[0]);
    const kx = 111_320.0 * Math.cos(aRad(p.lat));
    const ky = 110_540.0;
    let mejor = Number.MAX_VALUE;
    for (let i = 0; i < linea.length - 1; i++) {
      const ax = (linea[i].lon - p.lon) * kx;
      const ay = (linea[i].lat - p.lat) * ky;
      const bx = (linea[i + 1].lon - p.lon) * kx;
      const by = (linea[i + 1].lat - p.lat) * ky;
      const dx = bx - ax;
      const dy = by - ay;
      const largo2 = dx * dx + dy * dy;
      const t =
        largo2 === 0.0
          ? 0.0
          : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / largo2));
      const cx = ax + t * dx;
      const cy = ay + t * dy;
      mejor = Math.min(mejor, Math.sqrt(cx * cx + cy * cy));
    }
    return mejor;
  },

  /** Decodifica una polilínea codificada de Google/OSRM (precisión 5). */
  decodificarPolilinea(codificada: string): Punto[] {
    const puntos: Punto[] = [];
    let i = 0;
    let lat = 0;
    let lon = 0;
    while (i < codificada.length) {
      for (let eje = 0; eje <= 1; eje++) {
        let resultado = 0;
        let desplazamiento = 0;
        let b: number;
        do {
          b = codificada.charCodeAt(i++) - 63;
          resultado |= (b & 0x1f) << desplazamiento;
          desplazamiento += 5;
        } while (b >= 0x20);
        const delta = (resultado & 1) !== 0 ? ~(resultado >> 1) : resultado >> 1;
        if (eje === 0) lat += delta;
        else lon += delta;
      }
      puntos.push({ lat: lat / 1e5, lon: lon / 1e5 });
    }
    return puntos;
  },

  /** Codifica puntos como polilínea de Google/OSRM (precisión 5). */
  codificarPolilinea(puntos: Punto[]): string {
    let sb = '';
    let pLat = 0;
    let pLon = 0;
    for (const p of puntos) {
      const lat = Math.round(p.lat * 1e5);
      const lon = Math.round(p.lon * 1e5);
      for (const d of [lat - pLat, lon - pLon]) {
        let v = d < 0 ? ~(d << 1) : d << 1;
        while (v >= 0x20) {
          sb += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
          v >>>= 5;
        }
        sb += String.fromCharCode(v + 63);
      }
      pLat = lat;
      pLon = lon;
    }
    return sb;
  },

  /** Simplifica una polilínea (Douglas-Peucker) con tolerancia en metros. */
  simplificar(puntos: Punto[], toleranciaM: number): Punto[] {
    if (puntos.length < 3) return puntos;
    const guardar = new Array<boolean>(puntos.length).fill(false);
    guardar[0] = true;
    guardar[puntos.length - 1] = true;
    const pila: Array<[number, number]> = [[0, puntos.length - 1]];
    while (pila.length > 0) {
      const [a, b] = pila.pop()!;
      let maxD = 0.0;
      let idx = -1;
      for (let i = a + 1; i < b; i++) {
        const d = Geo.distanciaALinea(puntos[i], [puntos[a], puntos[b]]);
        if (d > maxD) {
          maxD = d;
          idx = i;
        }
      }
      if (idx >= 0 && maxD > toleranciaM) {
        guardar[idx] = true;
        pila.push([a, idx]);
        pila.push([idx, b]);
      }
    }
    return puntos.filter((_, i) => guardar[i]);
  },

  textoDistancia(m: number): string {
    return m < 1000 ? `${Math.round(m)} m` : `${(m / 1000.0).toFixed(1)} km`;
  },
};
