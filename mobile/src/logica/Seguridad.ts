import type { Punto } from '../core/Modelos.ts';

export class Seguridad {
  minutosQuieto = 10;
  detectarQuieto = true;
  avisosRiesgo = true;
  /** Vibrar el celular en avisos de zona, bajada, inactividad y desvío. */
  vibrarAlertas = true;

  private historial: Array<{ t: number; lat: number; lon: number }> = [];
  private ultimoHistorial = 0;

  registrar(p: Punto): void {
    const ahora = Date.now();
    if (ahora - this.ultimoHistorial < 20_000) return;
    this.ultimoHistorial = ahora;
    if (this.historial.length > 5_000) this.historial = [];
    this.historial.push({ t: ahora, lat: p.lat, lon: p.lon });
  }

  puntosHistorial(): number {
    return this.historial.length;
  }

  borrarHistorial(): void {
    this.historial = [];
  }
}
