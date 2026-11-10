import { Geo } from '../core/Geo.ts';
import type {
  Alerta,
  Comando,
  EstadoReloj,
  Hotel,
  MapaReloj,
  Modo,
  NavActual,
  Orden,
  Punto,
  Ruta,
  TipoAlerta,
  Tramo,
  TrazoMapa,
} from '../core/Modelos.ts';
import { type Ciudad, Ciudades } from '../datos/Ciudades.ts';
import { type Linea, Osm } from '../datos/Osm.ts';
import { type EvaluacionRiesgo, Riesgo } from '../datos/Riesgo.ts';
import { type RutaPie, Ruteo } from '../datos/Ruteo.ts';
import { Transporte } from '../datos/Transporte.ts';
import { EnlaceExpo } from './EnlaceExpo.ts';
import { Interprete } from './Interprete.ts';
import { Seguridad } from './Seguridad.ts';

export interface EstadoApp {
  ciudad: Ciudad | null;
  fuenteUbicacion: string;
  ubicacion: Punto | null;
  rumboActual: number | null;
  peticion: string;
  buscando: boolean;
  hoteles: Hotel[];
  orden: Orden;
  hotelElegido: Hotel | null;
  calculandoRutas: boolean;
  rutas: Ruta[];
  rutaSeleccionada: number;
  rutaActiva: number | null;
  nav: NavActual | null;
  riesgo: EvaluacionRiesgo | null;
  alerta: Alerta | null;
  soloVibracion: boolean;
  simulando: boolean;
  mensaje: string | null;
  bitacora: string[];
}

const LLEGADA_GIRO_M = 18.0;
const DESVIO_M = 45.0;
const RADIO_QUIETO_M = 40.0;
const MAX_HOTELES_RELOJ = 8;

export class Motor {
  readonly enlace = new EnlaceExpo();
  readonly osm = new Osm();
  readonly riesgo = new Riesgo(this.osm);
  readonly seguridad = new Seguridad();

  private _estado: EstadoApp = {
    ciudad: Ciudades.SLRC,
    fuenteUbicacion: 'gps',
    ubicacion: Ciudades.SLRC.centro,
    rumboActual: null,
    peticion: '',
    buscando: false,
    hoteles: [],
    orden: 'DISTANCIA',
    hotelElegido: null,
    calculandoRutas: false,
    rutas: [],
    rutaSeleccionada: 0,
    rutaActiva: null,
    nav: null,
    riesgo: null,
    alerta: null,
    soloVibracion: false,
    simulando: false,
    mensaje: 'Obteniendo ubicación GPS del celular…',
    bitacora: [],
  };

  private oyentes = new Set<() => void>();

  // Navegación
  private tramos: Tramo[] = [];
  private tramoIdx = 0;
  private pie: RutaPie | null = null;
  private pasoIdx = 1;
  private fueraDeRuta = 0;
  private avisoBajarse = 0;
  private ultimaPos: Punto | null = null;
  private rumboGpsReciente: number | null = null;
  private rumboBrujula: number | null = null;
  private ultimaLocalidadGps: Punto | null = null;

  // Seguimiento de quietud y riesgo
  private anclaQuieto: Punto | null = null;
  private tiempoAncla = Date.now();
  private ultimoRiesgoPos: Punto | null = null;
  private timerSimulacion: ReturnType<typeof setInterval> | null = null;
  private timerPublicar: ReturnType<typeof setTimeout> | null = null;
  private ultimoJsonReloj = '';
  private siguienteAlerta = Date.now();

  get estado(): EstadoApp {
    return this._estado;
  }

  suscribir(cb: () => void): () => void {
    this.oyentes.add(cb);
    return () => this.oyentes.delete(cb);
  }

  iniciar(): void {
    this.enlace.iniciar();
    this.enlace.enComando((c) => this.atender(c));
    this.programarPublicacion(true);
  }

  detener(): void {
    this.enlace.detener();
    this.detenerSimulacion();
  }

  actualizarBrujula(grados: number): void {
    const norm = Math.round(((grados % 360) + 360) % 360) % 360;
    this.rumboBrujula = norm;
    const prev = this._estado.rumboActual;
    if (prev !== null && Math.abs(Geo.diferenciaAngulo(norm, prev)) < 2) {
      return;
    }
    this._estado = { ...this._estado, rumboActual: norm };
    if (this._estado.nav && this._estado.ubicacion) {
      this.actualizarNav(this._estado.ubicacion, this._estado.hotelElegido?.nombre ?? 'Destino');
    } else {
      this.actualizar({ rumboActual: norm });
    }
  }

  private actualizar(parcial: Partial<EstadoApp> | ((prev: EstadoApp) => Partial<EstadoApp>)): void {
    const cambios = typeof parcial === 'function' ? parcial(this._estado) : parcial;
    this._estado = { ...this._estado, ...cambios };
    for (const cb of this.oyentes) cb();
    this.programarPublicacion(false);
  }

  private programarPublicacion(forzar: boolean): void {
    const r = this.paraReloj(this._estado);
    const huella = JSON.stringify({ ...r, generado: 0 });
    if (!forzar && huella === this.ultimoJsonReloj) return;
    this.ultimoJsonReloj = huella;
    if (forzar) {
      void this.enlace.publicar(r);
      return;
    }
    if (this.timerPublicar) return;
    this.timerPublicar = setTimeout(() => {
      this.timerPublicar = null;
      void this.enlace.publicar(this.paraReloj(this._estado));
    }, 150);
  }

  atender(c: Comando): void {
    this.anotar(`Reloj: ${c.tipo}${c.arg ? ` (${c.arg})` : ''}`);
    switch (c.tipo) {
      case 'PEDIR_ESTADO':
        this.programarPublicacion(true);
        break;
      case 'BUSCAR_HOTELES':
        void this.buscar(c.arg || 'hotel barato cerca');
        break;
      case 'ORDENAR':
        this.ordenar((c.arg as Orden) || 'DISTANCIA');
        break;
      case 'ELEGIR_HOTEL':
        if (c.arg) void this.elegirHotel(c.arg);
        break;
      case 'ELEGIR_RUTA':
        this.seleccionarRuta(parseInt(c.arg || '0', 10) || 0);
        break;
      case 'INICIAR_NAV':
        void this.iniciarNav(parseInt(c.arg || '0', 10) || 0);
        break;
      case 'DETENER_NAV':
        this.detenerNav();
        break;
      case 'RECALCULAR':
        void this.recalcular();
        break;
      case 'SIMULAR_NAV':
        this.simular(!this._estado.simulando);
        break;
      case 'ESTOY_BIEN':
        this.estoyBien();
        break;
      case 'SOLO_VIBRACION':
        this.actualizar((prev) => ({ soloVibracion: !prev.soloVibracion }));
        break;
    }
  }

  seleccionarRuta(indice: number): void {
    this.actualizar((prev) => ({
      rutaSeleccionada: Math.max(0, Math.min(indice, Math.max(0, prev.rutas.length - 1))),
    }));
  }

  usarFuente(fuente: string): void {
    const ciudadFija = Ciudades.porId(fuente);
    if (ciudadFija) {
      this.detenerSimulacion();
      this.ultimoRiesgoPos = null;
      this.actualizar({
        fuenteUbicacion: fuente,
        ciudad: ciudadFija,
        ubicacion: ciudadFija.centro,
        riesgo: null,
        mensaje: `Ubicación en ${ciudadFija.nombre} (centro)`,
      });
      void this.vigilarRiesgo(ciudadFija.centro);
    } else {
      this.detenerSimulacion();
      this.ultimoRiesgoPos = null;
      const p = this.ultimaPos ?? this._estado.ubicacion;
      const ciudad = p ? Ciudades.paraGps(p, this._estado.ciudad?.nombre) : this._estado.ciudad;
      this.actualizar({
        fuenteUbicacion: 'gps',
        ciudad,
        riesgo: null,
        mensaje: 'Usando tu ubicación GPS actual del celular',
      });
      if (p) {
        void this.actualizarLocalidadGps(p);
      }
    }
  }

  nuevaUbicacion(p: Punto, rumboGps: number | null = null, simulada = false): void {
    const e = this._estado;
    if (!simulada && (e.fuenteUbicacion !== 'gps' || e.simulando)) return;
    const anterior = this.ultimaPos;
    if (rumboGps !== null && rumboGps >= 0) {
      this.rumboGpsReciente = rumboGps;
    } else if (anterior && Geo.distancia(anterior, p) > 4) {
      this.rumboGpsReciente = Geo.rumbo(anterior, p);
    }
    if (!simulada) {
      this.ultimaPos = p;
    }

    const ciudadConocida = Ciudades.detectar(p);
    const ciudad =
      ciudadConocida ??
      (e.fuenteUbicacion === 'gps'
        ? Ciudades.paraGps(p, e.ciudad?.id === 'gps_actual' ? e.ciudad.nombre : null)
        : e.ciudad);

    this.actualizar({
      ubicacion: p,
      ciudad,
      rumboActual: Math.round(this.rumboGpsReciente ?? this.rumboBrujula ?? 0),
    });

    if (!simulada && e.fuenteUbicacion === 'gps' && !ciudadConocida) {
      void this.actualizarLocalidadGps(p);
    }

    this.seguridad.registrar(p);
    this.avanzarNav(p);
    this.vigilarQuieto(p);
    void this.vigilarRiesgo(p);
  }

  private async actualizarLocalidadGps(p: Punto): Promise<void> {
    if (this.ultimaLocalidadGps && Geo.distancia(this.ultimaLocalidadGps, p) < 2000) return;
    this.ultimaLocalidadGps = p;
    const nombre = await this.osm.nombreLocalidad(p);
    if (nombre && this._estado.fuenteUbicacion === 'gps' && !Ciudades.detectar(p)) {
      this.actualizar({
        ciudad: Ciudades.paraGps(p, `${nombre} (GPS)`),
      });
    }
  }

  async buscar(frase: string): Promise<void> {
    const e = this._estado;
    const ubicacionBase = e.ubicacion ?? Ciudades.SLRC.centro;
    const ciudad = e.ciudad ?? Ciudades.paraGps(ubicacionBase);
    const pet = Interprete.interpretar(frase);

    this.actualizar({
      peticion: frase,
      buscando: true,
      mensaje: `Buscando alojamiento en ${ciudad.nombre}…`,
    });

    try {
      let centro = ubicacionBase;
      let descripcion = e.fuenteUbicacion === 'gps' ? 'cerca de tu ubicación GPS' : 'cerca de ti';
      if (pet.cercaDe) {
        const lugar = await this.osm.buscarLugar(pet.cercaDe, ciudad);
        if (lugar) {
          centro = lugar.punto;
          descripcion = `cerca de ${lugar.nombre}`;
        } else {
          descripcion = `cerca de ti (no encontré "${pet.cercaDe}")`;
        }
      } else if (pet.cercaDelCentro && ciudad.id !== 'gps_actual') {
        centro = ciudad.centro;
        descripcion = 'cerca del centro';
      }

      let lista = await this.osm.hoteles(ciudad, centro, 3_000);
      if (lista.length < 3) {
        lista = await this.osm.hoteles(ciudad, centro, 8_000);
      }
      if (lista.length < 2) {
        lista = await this.osm.hoteles(ciudad, centro, 15_000);
      }
      const desde = ubicacionBase;
      lista = lista.map((h) => ({
        ...h,
        distanciaM: Math.round(Geo.distancia(desde, h.punto)),
      }));

      let nota = '';
      if (pet.tipos.size > 0) {
        const filtrados = lista.filter((it) => pet.tipos.has(it.tipo));
        if (filtrados.length > 0) lista = filtrados;
        else nota = ' (no hubo del tipo pedido; muestro todos)';
      }
      const tope = pet.presupuestoMax ?? (pet.barato ? ciudad.preciosNoche.hotel : null);
      if (tope !== null) {
        const filtrados = lista.filter((it) => it.precioEstimado <= tope);
        if (filtrados.length > 0) lista = filtrados;
        else nota += ` (ninguno bajo $${tope})`;
      }

      this.actualizar({
        buscando: false,
        hoteles: this.ordenarLista(lista, pet.orden),
        orden: pet.orden,
        hotelElegido: null,
        rutas: [],
        rutaSeleccionada: 0,
        mensaje: `${lista.length} opciones ${descripcion}${nota}. Precios estimados.`,
      });
    } catch (err: any) {
      this.actualizar({
        buscando: false,
        mensaje: `Error al buscar: ${err?.message ?? err}`,
      });
    }
  }

  ordenar(orden: Orden): void {
    this.actualizar((prev) => ({
      orden,
      hoteles: this.ordenarLista(prev.hoteles, orden),
    }));
  }

  async elegirHotel(id: string): Promise<void> {
    const e = this._estado;
    const hotel = e.hoteles.find((it) => it.id === id);
    const origen = e.ubicacion ?? e.ciudad?.centro ?? Ciudades.SLRC.centro;
    const ciudad = e.ciudad ?? Ciudades.paraGps(origen);
    if (!hotel) return;

    this.actualizar({
      hotelElegido: hotel,
      calculandoRutas: true,
      rutas: [],
      rutaSeleccionada: 0,
      mensaje: `Calculando cómo llegar a ${hotel.nombre}…`,
    });

    const fijas = this.osm.lineasFijas(ciudad);
    this.mostrarRutas(ciudad, origen, hotel, fijas, ciudad.id === 'slrc');

    if (ciudad.id !== 'slrc') {
      try {
        const camiones = await this.osm.lineasDeCamion(origen, hotel.punto);
        if (this._estado.hotelElegido?.id !== hotel.id) return;
        this.mostrarRutas(ciudad, origen, hotel, [...fijas, ...camiones], true);
      } catch {
        if (this._estado.hotelElegido?.id === hotel.id) {
          this.mostrarRutas(ciudad, origen, hotel, fijas, true);
        }
      }
    }
  }

  private mostrarRutas(ciudad: Ciudad, origen: Punto, hotel: Hotel, lineas: Linea[], esFinal: boolean): void {
    const rutas = new Transporte(ciudad).planear(origen, hotel.punto, lineas);
    const sinTransporte = !rutas.some((r) =>
      r.tramos.some((it) => it.modo !== 'CAMINAR' && it.modo !== 'TAXI'),
    );
    const extra = esFinal ? '' : ' Buscando transporte cercano…';
    this.actualizar((prev) => {
      if (prev.rutaActiva !== null) return { calculandoRutas: false };
      return {
        calculandoRutas: !esFinal,
        rutas,
        mensaje:
          (sinTransporte
            ? `Para este trayecto en ${ciudad.nombre}: caminar o taxi.`
            : `${rutas.length} opciones de ruta. Tiempos estimados.`) + extra,
      };
    });
  }

  async iniciarNav(indice: number): Promise<void> {
    const e = this._estado;
    const ruta = e.rutas[indice];
    if (!ruta) return;
    this.tramos = ruta.tramos.map((t) => ({ ...t }));
    this.tramoIdx = 0;
    this.actualizar({ rutaActiva: indice, rutaSeleccionada: indice });
    await this.comenzarTramo();
  }

  detenerNav(): void {
    this.detenerSimulacion();
    this.tramos = [];
    this.pie = null;
    this.actualizar({ nav: null, rutaActiva: null, mensaje: 'Navegación detenida' });
  }

  async recalcular(): Promise<void> {
    if (this.tramos.length === 0) {
      if (this._estado.hotelElegido) {
        await this.elegirHotel(this._estado.hotelElegido.id);
      }
      return;
    }
    await this.comenzarTramo();
  }

  private async comenzarTramo(): Promise<void> {
    const t = this.tramos[this.tramoIdx];
    const destinoNombre = this._estado.hotelElegido?.nombre ?? 'Destino';
    if (!t) {
      this.pie = null;
      this.detenerSimulacion();
      this.actualizar({
        nav: null,
        rutaActiva: null,
        alerta: this.crearAlerta('INFO', `Llegaste a ${destinoNombre}`),
      });
      return;
    }
    const aqui = this._estado.ubicacion ?? t.desdePunto;
    const meta = t.hastaPunto;
    if (!aqui || !meta) return;

    this.pasoIdx = 1;
    this.fueraDeRuta = 0;
    this.avisoBajarse = 0;

    if (t.modo === 'CAMINAR') {
      try {
        const opciones = await Ruteo.caminar(aqui, meta, false);
        this.pie = opciones[0] ?? null;
      } catch {
        this.pie = null;
      }
      const puntosPie = this.pie?.puntos;
      if (puntosPie && puntosPie.length > 0) {
        const simplificados = Geo.simplificar(puntosPie, 8.0);
        this.tramos = this.tramos.map((tr, idx) =>
          idx === this.tramoIdx ? { ...tr, trazo: simplificados } : tr,
        );
        const activa = this._estado.rutaActiva;
        if (activa !== null) {
          this.actualizar((est) => ({
            rutas: est.rutas.map((r, rIdx) => (rIdx === activa ? { ...r, tramos: this.tramos } : r)),
          }));
        }
      }
    } else {
      this.pie = null;
    }
    this.actualizarNav(aqui, destinoNombre);
  }

  private avanzarNav(p: Punto): void {
    const t = this.tramos[this.tramoIdx];
    if (!t || !t.hastaPunto) return;
    const meta = t.hastaPunto;
    const r = this.pie;

    if (t.modo === 'CAMINAR' && r) {
      while (this.pasoIdx < r.pasos.length - 1 && Geo.distancia(p, r.pasos[this.pasoIdx].punto) < LLEGADA_GIRO_M) {
        this.pasoIdx++;
      }
      if (Geo.distancia(p, meta) < LLEGADA_GIRO_M) {
        this.tramoIdx++;
        void this.comenzarTramo();
        return;
      }
      if (Geo.distanciaALinea(p, r.puntos) > DESVIO_M) {
        if (++this.fueraDeRuta >= 2) {
          this.fueraDeRuta = 0;
          this.actualizar({ alerta: this.crearAlerta('DESVIO', 'Te desviaste. Recalculando…') });
          void this.comenzarTramo();
          return;
        }
      } else {
        this.fueraDeRuta = 0;
      }
    } else {
      const d = Geo.distancia(p, meta);
      const limite = t.modo === 'CAMINAR' || t.modo === 'TAXI' ? LLEGADA_GIRO_M : 60.0;
      if (d < limite) {
        this.tramoIdx++;
        void this.comenzarTramo();
        return;
      }
      if (t.modo !== 'CAMINAR' && t.modo !== 'TAXI') {
        if (d < 700 && this.avisoBajarse === 0) {
          this.avisoBajarse = 1;
          this.actualizar({ alerta: this.crearAlerta('INFO', `Prepárate: bajas en ${t.hasta}`) });
        }
      }
    }
    this.actualizarNav(p, this._estado.hotelElegido?.nombre ?? 'Destino');
  }

  private actualizarNav(p: Punto, destino: string): void {
    const t = this.tramos[this.tramoIdx];
    if (!t || !t.hastaPunto) return;
    const meta = t.hastaPunto;
    const r = this.pie;
    const restantesTramos = this.tramos.slice(this.tramoIdx + 1).reduce((acc, it) => acc + it.minutos, 0);

    let nav: NavActual;
    if (t.modo === 'CAMINAR' && r && r.pasos.length > 1) {
      const idx = Math.min(this.pasoIdx, r.pasos.length - 1);
      const paso = r.pasos[idx];
      const alGiro = Math.round(Geo.distancia(p, paso.punto));
      const resto =
        alGiro + r.pasos.slice(idx).reduce((acc, it) => acc + it.distanciaM, 0);
      nav = {
        destino,
        giro: paso.giro,
        calle: paso.calle,
        metrosAlGiro: alGiro,
        flechaGrados: this.flecha(p, paso.punto),
        metrosRestantes: resto,
        minutosRestantes: Math.round(resto / 80.0) + restantesTramos,
        indicacion: this.siguienteTransporte(),
        rumboObjetivo: Math.round(Geo.rumbo(p, paso.punto)),
        puntoGiro: paso.punto,
        simulando: this._estado.simulando,
      };
    } else {
      const d = Math.round(Geo.distancia(p, meta));
      let ind: string | null = null;
      if (t.modo === 'TAXI') {
        ind = `Pide taxi/app a ${destino} (≈ $${Math.round(t.costo)})`;
      } else if (t.modo !== 'CAMINAR') {
        ind = `${t.linea}: de ${t.desde} a ${t.hasta} (${t.paradas ?? 0} paradas)`;
      }
      nav = {
        destino,
        giro: t.modo === 'CAMINAR' ? 'RECTO' : 'SALIDA',
        calle: t.hasta,
        metrosAlGiro: d,
        flechaGrados: this.flecha(p, meta),
        metrosRestantes: d,
        minutosRestantes: Math.round(d / this.velocidadMMin(t.modo)) + restantesTramos,
        indicacion: ind,
        rumboObjetivo: Math.round(Geo.rumbo(p, meta)),
        puntoGiro: meta,
        simulando: this._estado.simulando,
      };
    }
    this.actualizar({ nav });
  }

  private siguienteTransporte(): string | null {
    const sig = this.tramos[this.tramoIdx + 1];
    if (!sig || sig.modo === 'CAMINAR') return null;
    return sig.modo === 'TAXI' ? 'Después: taxi' : `Después: ${sig.linea} en ${sig.desde}`;
  }

  private flecha(p: Punto, objetivo: Punto): number {
    const r = Geo.rumbo(p, objetivo);
    const ref = this.rumboBrujula ?? this.rumboGpsReciente;
    return ref !== null ? Geo.diferenciaAngulo(r, ref) : Math.round(r);
  }

  private velocidadMMin(m: Modo): number {
    if (m === 'CAMINAR') return 80.0;
    if (m === 'TAXI') return 350.0;
    return (Transporte.velocidad(m) * 1000) / 60.0;
  }

  simular(activar: boolean): void {
    if (!activar) {
      this.detenerSimulacion();
      return;
    }
    if (!this._estado.nav) {
      this.actualizar({ mensaje: 'Primero inicia una navegación' });
      return;
    }
    this.detenerSimulacion();
    this.actualizar((prev) => ({
      simulando: true,
      nav: prev.nav ? { ...prev.nav, simulando: true } : null,
    }));
    this.timerSimulacion = setInterval(() => {
      const t = this.tramos[this.tramoIdx];
      const aqui = this._estado.ubicacion;
      if (!t || !aqui) {
        this.detenerSimulacion();
        return;
      }
      const puntos =
        this.pie?.puntos ?? (t.trazo && t.trazo.length >= 2 ? t.trazo : null);
      let objetivo: Punto | null = null;
      if (puntos && puntos.length > 0) {
        const masCercano = this.indiceMasCercano(puntos, aqui);
        objetivo = puntos.slice(masCercano + 1).find((pt) => Geo.distancia(pt, aqui) > 3) ?? null;
      }
      if (!objetivo) objetivo = t.hastaPunto ?? null;
      if (!objetivo) {
        this.detenerSimulacion();
        return;
      }
      const paso = t.modo === 'CAMINAR' ? 12.0 : 90.0;
      const d = Geo.distancia(aqui, objetivo);
      const siguiente: Punto =
        d <= paso
          ? objetivo
          : {
              lat: aqui.lat + ((objetivo.lat - aqui.lat) * paso) / d,
              lon: aqui.lon + ((objetivo.lon - aqui.lon) * paso) / d,
            };
      this.nuevaUbicacion(siguiente, Geo.rumbo(aqui, siguiente), true);
    }, 1000);
  }

  private indiceMasCercano(puntos: Punto[], p: Punto): number {
    let idx = 0;
    let mejor = Infinity;
    for (let i = 0; i < puntos.length; i++) {
      const d = Geo.distancia(puntos[i], p);
      if (d < mejor) {
        mejor = d;
        idx = i;
      }
    }
    return idx;
  }

  private detenerSimulacion(): void {
    if (this.timerSimulacion) {
      clearInterval(this.timerSimulacion);
      this.timerSimulacion = null;
    }
    if (this._estado.simulando) {
      this.actualizar((prev) => ({
        simulando: false,
        nav: prev.nav ? { ...prev.nav, simulando: false } : null,
      }));
    }
  }

  estoyBien(): void {
    this.anclaQuieto = this._estado.ubicacion;
    this.tiempoAncla = Date.now();
    this.actualizar({ alerta: null });
  }

  cerrarAlerta(): void {
    this.actualizar({ alerta: null });
  }

  reportarZona(): void {
    const p = this._estado.ubicacion;
    if (!p) return;
    this.riesgo.reportar(p);
    this.actualizar({
      mensaje: 'Zona marcada como precaución. Te avisaré si vuelves a pasar.',
    });
  }

  private vigilarQuieto(p: Punto): void {
    const e = this._estado;
    if (!this.anclaQuieto || Geo.distancia(this.anclaQuieto, p) > RADIO_QUIETO_M) {
      this.anclaQuieto = p;
      this.tiempoAncla = Date.now();
      return;
    }
    if (!this.seguridad.detectarQuieto || !e.nav || e.alerta?.tipo === 'QUIETO') return;
    if (e.hotelElegido && Geo.distancia(e.hotelElegido.punto, p) < 150) return;
    const minutos = Math.trunc((Date.now() - this.tiempoAncla) / 60_000);
    if (minutos < this.seguridad.minutosQuieto) return;

    this.actualizar({
      alerta: this.crearAlerta('QUIETO', `Llevas ${minutos} min sin avanzar. ¿Todo bien?`, true),
    });
  }

  private async vigilarRiesgo(p: Punto): Promise<void> {
    if (!this.seguridad.avisosRiesgo) return;
    if (this.ultimoRiesgoPos && Geo.distancia(this.ultimoRiesgoPos, p) < 150) return;
    this.ultimoRiesgoPos = p;
    const ciudad = this._estado.ciudad;
    if (!ciudad) return;

    const ev = await this.riesgo.evaluar(ciudad, p);
    const antes = this._estado.riesgo?.nivel;
    const sube =
      (ev.nivel === 'ALTO' && antes !== 'ALTO') ||
      (ev.nivel === 'MEDIO' &&
        (antes === 'BAJO' || antes === 'DESCONOCIDO' || !antes) &&
        this._estado.nav !== null);
    this.actualizar({
      riesgo: ev,
      ...(sube && ev.nivel !== 'DESCONOCIDO'
        ? { alerta: this.crearAlerta('RIESGO', `Precaución (${ev.nivel.toLowerCase()}): ${ev.motivo}`) }
        : {}),
    });
  }

  private paraReloj(e: EstadoApp): EstadoReloj {
    const rutaMapa =
      e.rutaActiva !== null ? e.rutas[e.rutaActiva] : e.rutas[e.rutaSeleccionada];
    const trazos: TrazoMapa[] = [];
    for (const t of rutaMapa?.tramos ?? []) {
      const pts =
        t.trazo && t.trazo.length > 0
          ? t.trazo
          : ([t.desdePunto, t.hastaPunto].filter(Boolean) as Punto[]);
      if (pts.length >= 2) {
        trazos.push({ modo: t.modo, puntos: Geo.simplificar(pts, 14.0) });
      }
    }
    const mapa: MapaReloj | null =
      e.ubicacion || e.ciudad || e.hotelElegido || trazos.length > 0
        ? {
            yo: e.ubicacion ?? e.ciudad?.centro ?? null,
            destino: e.hotelElegido?.punto ?? null,
            destinoNombre: e.hotelElegido?.nombre ?? '',
            trazos,
            rumbo: Math.round(e.rumboActual ?? 0),
          }
        : null;

    return {
      ciudad: e.ciudad?.nombre ?? '',
      buscando: e.buscando || e.calculandoRutas,
      hoteles: e.hoteles.slice(0, MAX_HOTELES_RELOJ).map((h) => ({
        ...h,
        telefono: null,
        web: null,
        direccion: null,
      })),
      orden: e.orden,
      hotelElegido: e.hotelElegido?.id ?? null,
      rutas: e.rutas,
      nav: e.nav,
      alerta: e.alerta,
      soloVibracion: e.soloVibracion,
      mensaje: e.mensaje,
      mapa,
      generado: Date.now(),
    };
  }

  private ordenarLista(l: Hotel[], o: Orden): Hotel[] {
    const copia = [...l];
    if (o === 'DISTANCIA') {
      return copia.sort((a, b) => a.distanciaM - b.distanciaM);
    }
    if (o === 'PRECIO') {
      return copia.sort((a, b) => a.precioEstimado - b.precioEstimado || a.distanciaM - b.distanciaM);
    }
    return copia.sort((a, b) => b.estrellas - a.estrellas || a.distanciaM - b.distanciaM);
  }

  private crearAlerta(tipo: TipoAlerta, texto: string, pideRespuesta = false): Alerta {
    return {
      id: this.siguienteAlerta++,
      tipo,
      texto,
      pideRespuesta,
    };
  }

  anotar(texto: string): void {
    const d = new Date();
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    this.actualizar((prev) => ({
      bitacora: [`${hh}:${mm}:${ss}  ${texto}`, ...prev.bitacora].slice(0, 40),
    }));
  }
}
