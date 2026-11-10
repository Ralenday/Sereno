import Constants from 'expo-constants';
import { type Comando, type EstadoReloj, estadoRelojAJson } from '../core/Modelos.ts';

export const PUERTO_PUENTE_HTTP = 19888;
export const PUERTO_TELEFONO_TCP = 19886;
export const PUERTO_RELOJ_TCP = 19887;
const INTERVALO_RUMBO_MS = 80;

function detectarHostPorDefecto(): string {
  try {
    const hostUri =
      Constants.expoConfig?.hostUri ??
      (Constants as any).manifest2?.extra?.expoGo?.debuggerHost ??
      (Constants as any).manifest?.debuggerHost;
    if (typeof hostUri === 'string' && hostUri.includes(':')) {
      return hostUri.split(':')[0];
    }
  } catch {}
  return 'localhost';
}

export class EnlaceExpo {
  private host = detectarHostPorDefecto();
  private _conectado = false;
  private _puenteActivo = false;
  private oyentesEstado = new Set<() => void>();
  private oyentesComandos = new Set<(c: Comando) => void>();
  private timerPoll: ReturnType<typeof setInterval> | null = null;
  // Brújula: una sola petición en vuelo; si llegan lecturas mientras tanto, solo se manda la última
  private rumboPendiente: number | null = null;
  private rumboEnVuelo = false;
  private ultimoEnvioRumbo = 0;
  private timerRumbo: ReturnType<typeof setTimeout> | null = null;

  get conectado(): boolean {
    return this._conectado;
  }

  get puenteActivo(): boolean {
    return this._puenteActivo;
  }

  get urlPuente(): string {
    return `http://${this.host}:${PUERTO_PUENTE_HTTP}`;
  }

  get hostActual(): string {
    return this.host;
  }

  cambiarHost(nuevoHost: string): void {
    const limpio = nuevoHost
      .trim()
      .replace(/^https?:\/\//, '')
      .replace(/:\d+.*$/, '');
    if (limpio) {
      this.host = limpio;
      this.notificar();
      void this.hacerPoll();
    }
  }

  iniciar(): void {
    if (this.timerPoll) return;
    void this.hacerPoll();
    this.timerPoll = setInterval(() => {
      void this.hacerPoll();
    }, 900);
  }

  detener(): void {
    if (this.timerPoll) {
      clearInterval(this.timerPoll);
      this.timerPoll = null;
    }
  }

  suscribir(cb: () => void): () => void {
    this.oyentesEstado.add(cb);
    return () => this.oyentesEstado.delete(cb);
  }

  enComando(cb: (c: Comando) => void): () => void {
    this.oyentesComandos.add(cb);
    return () => this.oyentesComandos.delete(cb);
  }

  /**
   * Envía solo el rumbo de la brújula al reloj (unos bytes). Limita a ~12 envíos/s y descarta las
   * lecturas intermedias para que nunca se acumule una cola de peticiones atrasadas.
   */
  publicarRumbo(grados: number): void {
    this.rumboPendiente = grados;
    this.bombearRumbo();
  }

  private bombearRumbo(): void {
    if (this.rumboEnVuelo || this.timerRumbo || this.rumboPendiente === null) return;
    const espera = INTERVALO_RUMBO_MS - (Date.now() - this.ultimoEnvioRumbo);
    if (espera > 0) {
      this.timerRumbo = setTimeout(() => {
        this.timerRumbo = null;
        this.bombearRumbo();
      }, espera);
      return;
    }
    const valor = this.rumboPendiente;
    this.rumboPendiente = null;
    this.rumboEnVuelo = true;
    this.ultimoEnvioRumbo = Date.now();
    const control = new AbortController();
    const corte = setTimeout(() => control.abort(), 1500);
    fetch(`${this.urlPuente}/rumbo`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: String(valor),
      signal: control.signal,
    })
      .catch(() => {})
      .finally(() => {
        clearTimeout(corte);
        this.rumboEnVuelo = false;
        this.bombearRumbo();
      });
  }

  async publicar(estado: EstadoReloj): Promise<void> {
    try {
      const cuerpo = JSON.stringify(estadoRelojAJson(estado));
      const res = await fetch(`${this.urlPuente}/estado`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: cuerpo,
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      this.actualizarConexion(true, Boolean(data?.relojConectado));
      this.despacharComandos(data?.comandos);
    } catch {
      this.actualizarConexion(false, false);
    }
  }

  private async hacerPoll(): Promise<void> {
    try {
      const res = await fetch(`${this.urlPuente}/poll`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      this.actualizarConexion(true, Boolean(data?.relojConectado));
      this.despacharComandos(data?.comandos);
    } catch {
      this.actualizarConexion(false, false);
    }
  }

  private actualizarConexion(puente: boolean, reloj: boolean): void {
    if (this._puenteActivo !== puente || this._conectado !== reloj) {
      this._puenteActivo = puente;
      this._conectado = reloj;
      this.notificar();
    }
  }

  private despacharComandos(lista: unknown): void {
    if (!Array.isArray(lista)) return;
    for (const item of lista) {
      if (item && typeof item.tipo === 'string') {
        const cmd: Comando = { tipo: item.tipo, arg: item.arg ?? null };
        for (const cb of this.oyentesComandos) cb(cmd);
      }
    }
  }

  private notificar(): void {
    for (const cb of this.oyentesEstado) cb();
  }
}
