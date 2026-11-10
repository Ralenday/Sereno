import { Directory, File, Paths } from 'expo-file-system';
import { Platform } from 'react-native';

/**
 * Teselas de tile.openstreetmap.org con caché en disco.
 *
 * La política de uso de OSM pide que cada app se identifique con un User-Agent propio y que guarde
 * las teselas localmente. El componente <Image> de Expo Go en Android no siempre manda las cabeceras
 * personalizadas (OSM respondía con la tesela "Access blocked"), así que aquí se descargan con
 * expo-file-system, que sí las envía, y se reutilizan desde la caché durante 7 días.
 */

export const USER_AGENT_SERENO = 'Sereno/1.0 (app escolar de viaje; github.com/Ralenday/Sereno)';
const CABECERAS = { 'User-Agent': USER_AGENT_SERENO, Referer: 'https://github.com/Ralenday/Sereno' };
const VIGENCIA_MS = 7 * 24 * 3600 * 1000;
const MAX_DESCARGAS = 4;

const enMemoria = new Map<string, string>();
const pendientes = new Map<string, Promise<string | null>>();
const cola: Array<() => void> = [];
let activas = 0;

export function urlTesela(z: number, x: number, y: number): string {
  return `https://tile.openstreetmap.org/${z}/${x}/${y}.png`;
}

function carpeta(): Directory {
  const d = new Directory(Paths.cache, 'teselas_osm');
  if (!d.exists) d.create({ intermediates: true, idempotent: true });
  return d;
}

async function turno<T>(trabajo: () => Promise<T>): Promise<T> {
  if (activas >= MAX_DESCARGAS) await new Promise<void>((r) => cola.push(r));
  activas++;
  try {
    return await trabajo();
  } finally {
    activas--;
    cola.shift()?.();
  }
}

/** Devuelve una URI local (file://) de la tesela, descargándola si hace falta. null si falla. */
export function teselaLocal(z: number, x: number, y: number): Promise<string | null> {
  const clave = `${z}_${x}_${y}`;
  const lista = enMemoria.get(clave);
  if (lista) return Promise.resolve(lista);
  if (Platform.OS === 'web') return Promise.resolve(urlTesela(z, x, y));
  const enCurso = pendientes.get(clave);
  if (enCurso) return enCurso;

  const p = (async () => {
    try {
      const archivo = new File(carpeta(), `${clave}.png`);
      const vigente =
        archivo.exists &&
        (archivo.size ?? 0) > 0 &&
        Date.now() - (archivo.modificationTime ?? 0) < VIGENCIA_MS;
      if (!vigente) {
        await turno(() =>
          File.downloadFileAsync(urlTesela(z, x, y), archivo, { headers: CABECERAS, idempotent: true }),
        );
      }
      enMemoria.set(clave, archivo.uri);
      return archivo.uri;
    } catch {
      return null;
    } finally {
      pendientes.delete(clave);
    }
  })();
  pendientes.set(clave, p);
  return p;
}
