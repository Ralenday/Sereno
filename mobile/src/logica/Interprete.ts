import type { Orden } from '../core/Modelos.ts';

export interface Peticion {
  tipos: Set<string>;
  presupuestoMax: number | null;
  barato: boolean;
  orden: Orden;
  cercaDelCentro: boolean;
  cercaDe: string | null;
}

export const Interprete = {
  interpretar(frase: string): Peticion {
    const t = Interprete.normalizar(frase);
    const tipos = new Set<string>();
    if (/\bhostal|hostel|albergue/.test(t)) tipos.add('hostel');
    if (/\bmotel/.test(t)) tipos.add('motel');
    if (/airbnb|departamento|depa\b|casa de huespedes|posada/.test(t)) {
      tipos.add('guest_house');
      tipos.add('apartment');
    }
    if (/\bhotel/.test(t)) tipos.add('hotel');

    const matchNum = /(?:menos de|maximo|hasta|\$)\s*\$?\s*(\d{3,5})/.exec(t);
    const numero = matchNum ? parseInt(matchNum[1], 10) : null;
    const barato = /barat|economic|bajo costo|accesible|poco dinero/.test(t);
    let orden: Orden = 'DISTANCIA';
    if (/mejor valorad|bueno|lujo|estrellas|calidad/.test(t)) {
      orden = 'VALORACION';
    } else if (barato || numero !== null) {
      orden = 'PRECIO';
    }

    const centro = /\bcentro\b/.test(t) && !/centro comercial|centro de convenciones/.test(t);
    const matchCerca = /cerca (?:de|del|de la|de los|de las) (.+?)(?: y |,|\.|$)/.exec(t);
    let cercaDe: string | null = null;
    if (matchCerca) {
      let c = matchCerca[1].replace(/^la\s+/, '').replace(/^el\s+/, '').trim();
      if (c !== '' && c !== 'centro' && c !== 'aqui' && c !== 'mi') {
        cercaDe = c;
      }
    }

    return {
      tipos,
      presupuestoMax: numero,
      barato,
      orden,
      cercaDelCentro: centro,
      cercaDe,
    };
  },

  normalizar(s: string): string {
    return s
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '');
  },
};
