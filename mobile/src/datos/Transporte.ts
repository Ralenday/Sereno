import { Geo } from '../core/Geo.ts';
import { ETIQUETA_MODO, type Modo, type Punto, type Ruta, type Tramo } from '../core/Modelos.ts';
import { type Ciudad, Ciudades } from './Ciudades.ts';
import type { Linea } from './Osm.ts';

const MAX_CAMINAR_A_PARADA = 900.0;
const MAX_TRANSBORDO = 250.0;
const MAX_CAMINAR_TODO = 3_000.0;
const MAX_RUTAS = 4;
const PENALIZACION_TRANSBORDO_MIN = 4;
const FACTOR_CALLES = 1.3;
const VEL_CAMINAR_M_MIN = 80.0;
const VEL_TAXI_KMH = 22.0;

interface Cand {
  linea: Linea;
  i: number;
  metrosCaminar: number;
}

export class Transporte {
  readonly ciudad: Ciudad;

  constructor(ciudad: Ciudad) {
    this.ciudad = ciudad;
  }

  static minCaminar(metrosLineaRecta: number): number {
    return Math.ceil((metrosLineaRecta * FACTOR_CALLES) / VEL_CAMINAR_M_MIN);
  }

  static velocidad(m: Modo): number {
    switch (m) {
      case 'METRO':
        return 32.0;
      case 'TREN':
        return 45.0;
      case 'TREN_LIGERO':
        return 24.0;
      case 'METROBUS':
        return 19.0;
      case 'CABLEBUS':
        return 18.0;
      case 'TROLEBUS':
        return 15.0;
      default:
        return 14.0;
    }
  }

  static espera(m: Modo): number {
    switch (m) {
      case 'METRO':
        return 4;
      case 'METROBUS':
        return 5;
      case 'CABLEBUS':
        return 2;
      case 'TREN':
        return 8;
      case 'TREN_LIGERO':
        return 6;
      default:
        return 12;
    }
  }

  planear(origen: Punto, destino: Punto, lineas: Linea[]): Ruta[] {
    const rutas: Ruta[] = [];
    const directo = Geo.distancia(origen, destino);

    // 1) Caminar todo (si es razonable)
    if (directo <= MAX_CAMINAR_TODO) {
      const min = Transporte.minCaminar(directo);
      rutas.push({
        titulo: 'Caminando',
        tramos: [this.tramoCaminar('Tu ubicación', 'Destino', origen, destino)],
        minutos: min,
        costo: 0.0,
        transbordos: 0,
        estimada: true,
      });
    }

    // 2) Transporte
    const cercaOrigen = this.candidatos(origen, lineas);
    const cercaDestino = this.candidatos(destino, lineas);
    const porLinea = new Map<number, Cand[]>();
    for (const c of cercaDestino) {
      const arr = porLinea.get(c.linea.id) ?? [];
      arr.push(c);
      porLinea.set(c.linea.id, arr);
    }

    // Directos
    for (const a of cercaOrigen) {
      const candidatosDest = (porLinea.get(a.linea.id) ?? []).filter((it) => it.i > a.i);
      if (candidatosDest.length === 0) continue;
      let b = candidatosDest[0];
      for (const cand of candidatosDest) {
        if (cand.metrosCaminar < b.metrosCaminar) b = cand;
      }
      rutas.push(this.armar(origen, destino, [[a.linea, a.i, b.i]]));
    }

    // Un transbordo (paradas a <= 250 m entre líneas distintas)
    for (const a of cercaOrigen) {
      for (const b of cercaDestino) {
        if (a.linea.id === b.linea.id || (a.linea.ref === b.linea.ref && a.linea.modo === b.linea.modo)) {
          continue;
        }
        let mejor: [number, number, number] | null = null;
        for (let x = a.i + 1; x < a.linea.paradas.length; x++) {
          for (let y = 0; y < b.i; y++) {
            const d = Geo.distancia(a.linea.paradas[x].punto, b.linea.paradas[y].punto);
            if (d <= MAX_TRANSBORDO && (mejor === null || d < mejor[2])) {
              mejor = [x, y, d];
            }
          }
        }
        if (!mejor) continue;
        rutas.push(
          this.armar(origen, destino, [
            [a.linea, a.i, mejor[0]],
            [b.linea, mejor[1], b.i],
          ]),
        );
      }
    }

    // 3) Taxi / app (siempre como respaldo)
    const metrosAuto = directo * 1.35;
    const costoTaxi = Math.round(Ciudades.costoTaxi(this.ciudad, metrosAuto, origen, destino));
    const notaTaxi = this.ciudad.taxiPorZona ? `según zona, desde $${Math.round(this.ciudad.taxiBanderazo)}` : '';
    const minTaxi = Math.ceil((metrosAuto / 1000.0 / VEL_TAXI_KMH) * 60.0) + 5;
    rutas.push({
      titulo: 'Taxi o app',
      tramos: [
        {
          modo: 'TAXI',
          linea: notaTaxi,
          desde: 'Tu ubicación',
          hasta: 'Destino',
          minutos: minTaxi,
          costo: costoTaxi,
          paradas: 0,
          desdePunto: origen,
          hastaPunto: destino,
          trazo: [origen, destino],
        },
      ],
      minutos: minTaxi,
      costo: costoTaxi,
      transbordos: 0,
      estimada: true,
    });

    // Quedarse con la mejor opción por combinación de líneas, ordenadas por tiempo
    const porTitulo = new Map<string, Ruta>();
    for (const r of rutas) {
      const prev = porTitulo.get(r.titulo);
      if (!prev || r.minutos < prev.minutos) {
        porTitulo.set(r.titulo, r);
      }
    }
    return Array.from(porTitulo.values())
      .sort((a, b) => {
        const aTaxi = a.tramos[0]?.modo === 'TAXI' ? 1 : 0;
        const bTaxi = b.tramos[0]?.modo === 'TAXI' ? 1 : 0;
        if (aTaxi !== bTaxi) return aTaxi - bTaxi;
        return a.minutos - b.minutos;
      })
      .slice(0, MAX_RUTAS);
  }

  private candidatos(p: Punto, lineas: Linea[]): Cand[] {
    const res: Cand[] = [];
    for (const l of lineas) {
      let mejor: Cand | null = null;
      for (let i = 0; i < l.paradas.length; i++) {
        const d = Geo.distancia(p, l.paradas[i].punto);
        if (d <= MAX_CAMINAR_A_PARADA && (mejor === null || d < mejor.metrosCaminar)) {
          mejor = { linea: l, i, metrosCaminar: d };
        }
      }
      if (mejor) res.push(mejor);
    }
    return res;
  }

  private armar(origen: Punto, destino: Punto, viajes: Array<[Linea, number, number]>): Ruta {
    const tramos: Tramo[] = [];
    let ultimoPunto = origen;
    let ultimoNombre = 'Tu ubicación';
    let modoAnterior: Modo | null = null;
    let redAnterior = '';

    for (const [l, i, j] of viajes) {
      const sube = l.paradas[i];
      const baja = l.paradas[j];
      tramos.push(this.tramoCaminar(ultimoNombre, sube.nombre, ultimoPunto, sube.punto));
      let metros = 0.0;
      for (let k = i; k < j; k++) {
        metros += Geo.distancia(l.paradas[k].punto, l.paradas[k + 1].punto);
      }
      const min = Math.ceil((metros / 1000.0 / Transporte.velocidad(l.modo)) * 60.0) + Transporte.espera(l.modo);
      const gratis =
        modoAnterior === l.modo &&
        redAnterior === l.red &&
        (l.modo === 'METRO' || l.modo === 'METROBUS');
      const costo = gratis ? 0.0 : (this.ciudad.tarifas[l.modo] ?? this.ciudad.tarifas.BUS ?? 0.0);
      const etiqueta = [ETIQUETA_MODO[l.modo], l.ref.trim() !== '' ? l.ref.trim() : null]
        .filter(Boolean)
        .join(' ');
      const puntosTramo = Geo.simplificar(
        l.paradas.slice(i, j + 1).map((it) => it.punto),
        15.0,
      );
      tramos.push({
        modo: l.modo,
        linea: etiqueta,
        desde: sube.nombre,
        hasta: baja.nombre,
        minutos: min,
        costo,
        paradas: j - i,
        desdePunto: sube.punto,
        hastaPunto: baja.punto,
        trazo: puntosTramo,
      });
      ultimoPunto = baja.punto;
      ultimoNombre = baja.nombre;
      modoAnterior = l.modo;
      redAnterior = l.red;
    }

    tramos.push(this.tramoCaminar(ultimoNombre, 'Destino', ultimoPunto, destino));
    const titulo = tramos
      .filter((it) => it.modo !== 'CAMINAR')
      .map((it) => it.linea)
      .join(' → ');
    const tramosFiltrados = tramos.filter((it) => it.modo !== 'CAMINAR' || it.minutos > 0);
    const minutosTotal =
      tramos.reduce((acc, it) => acc + it.minutos, 0) + (viajes.length - 1) * PENALIZACION_TRANSBORDO_MIN;
    const costoTotal = tramos.reduce((acc, it) => acc + it.costo, 0);

    return {
      titulo,
      tramos: tramosFiltrados,
      minutos: minutosTotal,
      costo: costoTotal,
      transbordos: viajes.length - 1,
      estimada: true,
    };
  }

  private tramoCaminar(desde: string, hasta: string, a: Punto, b: Punto): Tramo {
    return {
      modo: 'CAMINAR',
      linea: '',
      desde,
      hasta,
      minutos: Transporte.minCaminar(Geo.distancia(a, b)),
      costo: 0.0,
      paradas: 0,
      desdePunto: a,
      hastaPunto: b,
      trazo: [a, b],
    };
  }
}
