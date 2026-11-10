import * as Location from 'expo-location';
import { StatusBar } from 'expo-status-bar';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Modal,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import Svg, { Circle, Line, Path, Polygon } from 'react-native-svg';
import { Geo } from './src/core/Geo.ts';
import type { Hotel, Orden, Punto, Ruta } from './src/core/Modelos.ts';
import { Ciudades } from './src/datos/Ciudades.ts';
import { NOMBRE_TIPO } from './src/datos/Osm.ts';
import { ANIO_DATOS } from './src/datos/Riesgo.ts';
import { PUERTO_PUENTE_HTTP, PUERTO_RELOJ_TCP, PUERTO_TELEFONO_TCP } from './src/logica/EnlaceExpo.ts';
import { type EstadoApp, Motor } from './src/logica/Motor.ts';

// Paleta suave y descansada para la vista (tonos pizarra, salvia y azul sereno sobre fondo claro cálido)
const FONDO = '#F2F5F8';
const SUPERFICIE = '#FFFFFF';
const SUPERFICIE_SUAVE = '#EBF0F5';
const BORDE = '#D8E1EB';
const TEXTO_PRINCIPAL = '#1E293B';
const TEXTO_SECUNDARIO = '#5B6B7C';
const SALVIA = '#2E7D6B';
const SALVIA_FONDO = '#E3F2EE';
const AZUL_SERENO = '#3A6EA5';
const AZUL_FONDO = '#E6F0FA';
const AMBAR_SUAVE = '#9A6700';
const AMBAR_FONDO = '#FDF3DC';

function flechaTexto(grados: number): string {
  const g = ((grados % 360) + 360) % 360;
  const flechas = ['⬆', '↗', '➡', '↘', '⬇', '↙', '⬅', '↖'];
  return flechas[Math.trunc((g + 22) / 45) % 8];
}

function lonAX(lon: number, z: number): number {
  return ((lon + 180.0) / 360.0) * (1 << z);
}

function latAY(lat: number, z: number): number {
  const rad = (lat * Math.PI) / 180.0;
  return ((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0) * (1 << z);
}

export default function App() {
  const motorRef = useRef<Motor | null>(null);
  if (!motorRef.current) {
    motorRef.current = new Motor();
  }
  const motor = motorRef.current;

  const [estado, setEstado] = useState<EstadoApp>(motor.estado);
  const [pestana, setPestana] = useState<number>(0);
  const [conectadoReloj, setConectadoReloj] = useState<boolean>(motor.enlace.conectado);
  const [puenteActivo, setPuenteActivo] = useState<boolean>(motor.enlace.puenteActivo);

  const obtenerGpsActual = useCallback(
    async (buscarAlObtener = false) => {
      try {
        const { status } = await Location.requestForegroundPermissionsAsync();
        if (status !== 'granted') {
          return;
        }
        motor.usarFuente('gps');
        const conocida = await Location.getLastKnownPositionAsync();
        if (conocida) {
          motor.nuevaUbicacion(
            { lat: conocida.coords.latitude, lon: conocida.coords.longitude },
            conocida.coords.heading !== null && conocida.coords.heading >= 0
              ? conocida.coords.heading
              : null,
            false,
          );
        }
        const actual = await Location.getCurrentPositionAsync({
          accuracy: Location.Accuracy.Balanced,
        });
        motor.nuevaUbicacion(
          { lat: actual.coords.latitude, lon: actual.coords.longitude },
          actual.coords.heading !== null && actual.coords.heading >= 0
            ? actual.coords.heading
            : null,
          false,
        );
        if (buscarAlObtener) {
          void motor.buscar('hotel cerca');
        }
      } catch {
        if (buscarAlObtener) {
          void motor.buscar('hotel cerca');
        }
      }
    },
    [motor],
  );

  useEffect(() => {
    motor.iniciar();
    const desuscribirMotor = motor.suscribir(() => setEstado({ ...motor.estado }));
    const desuscribirEnlace = motor.enlace.suscribir(() => {
      setConectadoReloj(motor.enlace.conectado);
      setPuenteActivo(motor.enlace.puenteActivo);
    });

    let subPos: Location.LocationSubscription | null = null;
    let subHeading: Location.LocationSubscription | null = null;

    void (async () => {
      try {
        const { status } = await Location.requestForegroundPermissionsAsync();
        if (status !== 'granted') return;
        await obtenerGpsActual(false);
        subPos = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.Balanced,
            timeInterval: 3000,
            distanceInterval: 4,
          },
          (loc) => {
            motor.nuevaUbicacion(
              { lat: loc.coords.latitude, lon: loc.coords.longitude },
              loc.coords.heading !== null && loc.coords.heading >= 0 ? loc.coords.heading : null,
              false,
            );
          },
        );
        subHeading = await Location.watchHeadingAsync((h) => {
          const rumbo = h.trueHeading >= 0 ? h.trueHeading : h.magHeading;
          if (rumbo >= 0) motor.actualizarBrujula(rumbo);
        });
      } catch {}
    })();

    return () => {
      desuscribirMotor();
      desuscribirEnlace();
      subPos?.remove();
      subHeading?.remove();
      motor.detener();
    };
  }, [motor, obtenerGpsActual]);

  const alerta = estado.alerta;

  return (
    <SafeAreaView style={estilos.contenedor}>
      <StatusBar style="dark" />
      <View style={estilos.cuerpo}>
        {pestana === 0 && (
          <PantallaViaje e={estado} motor={motor} alUsarGps={obtenerGpsActual} />
        )}
        {pestana === 1 && <PantallaPreferencias e={estado} motor={motor} />}
        {pestana === 2 && (
          <PantallaReloj
            e={estado}
            motor={motor}
            conectado={conectadoReloj}
            puenteActivo={puenteActivo}
          />
        )}
      </View>

      <View style={estilos.barraInferior}>
        {[
          { ic: '🧭', et: 'Viaje' },
          { ic: '🛡', et: 'Avisos' },
          { ic: '⌚', et: 'Reloj' },
        ].map((item, i) => {
          const activo = pestana === i;
          return (
            <Pressable
              key={item.et}
              style={[estilos.itemBarra, activo && estilos.itemBarraActivo]}
              onPress={() => setPestana(i)}
            >
              <Text style={estilos.iconoBarra}>{item.ic}</Text>
              <Text style={[estilos.textoBarra, activo && { color: SALVIA, fontWeight: '700' }]}>
                {item.et}
              </Text>
            </Pressable>
          );
        })}
      </View>

      <Modal visible={Boolean(alerta)} transparent animationType="fade">
        <View style={estilos.modalFondo}>
          <View style={estilos.modalCaja}>
            <Text style={estilos.modalTitulo}>
              {alerta?.tipo === 'RIESGO' ? 'Aviso de zona' : 'Sereno'}
            </Text>
            <Text style={estilos.modalTexto}>{alerta?.texto}</Text>
            <View style={estilos.modalBotones}>
              <Pressable
                style={estilos.botonPrimario}
                onPress={() => (alerta?.pideRespuesta ? motor.estoyBien() : motor.cerrarAlerta())}
              >
                <Text style={estilos.textoBotonPrimario}>
                  {alerta?.pideRespuesta ? 'Todo bien, continuar' : 'Entendido'}
                </Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

// =================== VIAJE ===================

function PantallaViaje({
  e,
  motor,
  alUsarGps,
}: {
  e: EstadoApp;
  motor: Motor;
  alUsarGps: (buscarAlObtener?: boolean) => Promise<void>;
}) {
  const [texto, setTexto] = useState('Necesito hotel barato cerca del centro y transporte público');
  const rutaVisible =
    e.rutaActiva !== null ? e.rutas[e.rutaActiva] : e.rutas[e.rutaSeleccionada];

  return (
    <ScrollView contentContainerStyle={estilos.listaContenido}>
      <View style={estilos.filaEncabezado}>
        <View>
          <Text style={estilos.tituloPrincipal}>Sereno</Text>
          <Text style={estilos.subtitulo}>Compañero de viaje discreto</Text>
        </View>
        {e.rumboActual !== null && (
          <View style={estilos.insigniaBrujula}>
            <Text style={estilos.textoInsigniaBrujula}>🧭 {e.rumboActual}°</Text>
          </View>
        )}
      </View>

      {/* Selector de ubicación: GPS real del celular + San Luis Río Colorado + CDMX */}
      <View style={estilos.tarjeta}>
        <Text style={estilos.etiquetaSeccion}>¿Desde dónde buscas?</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginTop: 4 }}>
          <View style={estilos.filaChips}>
            <Chip
              activo={e.fuenteUbicacion === 'gps'}
              texto="📍 Mi ubicación (GPS)"
              onPress={() => void alUsarGps(true)}
            />
            {Ciudades.todas.map((c) => (
              <Chip
                key={c.id}
                activo={e.fuenteUbicacion === c.id}
                texto={c.nombre}
                onPress={() => {
                  motor.usarFuente(c.id);
                  void motor.buscar(texto);
                }}
              />
            ))}
          </View>
        </ScrollView>
        <Text style={estilos.textoSecundario}>
          {e.ciudad?.nombre ?? 'Ubicación actual'}
          {e.ubicacion ? ` · ${e.ubicacion.lat.toFixed(4)}, ${e.ubicacion.lon.toFixed(4)}` : ''}
        </Text>
        {e.riesgo && (
          <Text style={[estilos.textoPequeno, { color: TEXTOS_RIESGO[e.riesgo.nivel] ?? TEXTO_SECUNDARIO }]}>
            Zona: {e.riesgo.nivel.toLowerCase()} — {e.riesgo.motivo}
          </Text>
        )}
      </View>

      {/* Buscador */}
      <View style={estilos.tarjeta}>
        <TextInput
          value={texto}
          onChangeText={setTexto}
          placeholder="¿Qué necesitas? Ej.: hotel barato cerca"
          placeholderTextColor="#8B98A9"
          multiline
          style={estilos.campoTexto}
        />

        <View style={[estilos.filaBotones, { marginTop: 8, flexWrap: 'wrap' }]}>
          <Pressable
            style={[estilos.botonPrimario, e.buscando && { opacity: 0.6 }]}
            disabled={e.buscando}
            onPress={() => void motor.buscar(texto)}
          >
            <Text style={estilos.textoBotonPrimario}>Buscar alojamiento</Text>
          </Pressable>
          <Pressable
            style={estilos.botonSecundario}
            disabled={e.buscando}
            onPress={() => void alUsarGps(true)}
          >
            <Text style={estilos.textoBotonSecundario}>📍 Hoteles cerca de mi GPS</Text>
          </Pressable>
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginTop: 6 }}>
          <View style={estilos.filaChips}>
            {[
              'Hotel cerca de aquí',
              'Hostal barato',
              'Hotel mejor valorado cerca',
              'Motel menos de 800',
            ].map((s) => (
              <Pressable
                key={s}
                style={estilos.botonSugerencia}
                onPress={() => {
                  setTexto(s);
                  void motor.buscar(s);
                }}
              >
                <Text style={estilos.textoSugerencia}>{s}</Text>
              </Pressable>
            ))}
          </View>
        </ScrollView>
      </View>

      {(e.buscando || e.calculandoRutas) && (
        <ActivityIndicator color={SALVIA} style={{ marginVertical: 4 }} />
      )}
      {e.mensaje && (
        <View style={estilos.cajaMensaje}>
          <Text style={estilos.textoMensaje}>{e.mensaje}</Text>
        </View>
      )}

      {(e.ubicacion || e.hoteles.length > 0 || e.rutas.length > 0) && (
        <TarjetaMapaTelefono
          ubicacion={e.ubicacion ?? e.ciudad?.centro ?? null}
          rumboFlecha={e.nav?.flechaGrados ?? e.rumboActual ?? 0}
          hoteles={e.hoteles}
          hotelElegido={e.hotelElegido}
          ruta={rutaVisible ?? null}
          alElegirHotel={(h) => void motor.elegirHotel(h.id)}
        />
      )}

      {e.nav && (
        <View style={estilos.tarjetaNav}>
          <View style={estilos.filaEncabezado}>
            <Text style={estilos.tituloTarjeta}>🧭 Guiando hacia {e.nav.destino}</Text>
            <Text style={estilos.insigniaDistancia}>{Geo.textoDistancia(e.nav.metrosAlGiro)}</Text>
          </View>
          <Text style={estilos.textoPrincipal}>
            {flechaTexto(e.nav.flechaGrados)}  { e.nav.giro.toLowerCase().replace('_', ' ') } en{' '}
            <Text style={{ fontWeight: '700' }}>{e.nav.calle}</Text>
          </Text>
          {e.nav.indicacion && (
            <Text style={{ color: AZUL_SERENO, fontWeight: '600' }}>{e.nav.indicacion}</Text>
          )}
          <Text style={estilos.textoSecundario}>
            Faltan {Geo.textoDistancia(e.nav.metrosRestantes)} · ~{e.nav.minutosRestantes} min
          </Text>
          <View style={[estilos.filaBotones, { marginTop: 8 }]}>
            <Pressable
              style={estilos.botonPrimario}
              onPress={() => motor.simular(!e.simulando)}
            >
              <Text style={estilos.textoBotonPrimario}>
                {e.simulando ? '⏸ Pausar simulación' : '▶ Simular recorrido'}
              </Text>
            </Pressable>
            <Pressable style={estilos.botonSecundario} onPress={() => motor.detenerNav()}>
              <Text style={estilos.textoBotonSecundario}>Detener</Text>
            </Pressable>
          </View>
        </View>
      )}

      {e.hotelElegido && e.rutas.length > 0 && (
        <View style={{ gap: 8 }}>
          <Text style={estilos.tituloSeccion}>Rutas hacia {e.hotelElegido.nombre}</Text>
          {e.ciudad?.tarifaDescuento !== undefined && (
            <Text style={estilos.textoNota}>
              Combi en {e.ciudad.nombre}: ${Math.round(e.ciudad.tarifas.MICRO ?? 14)} general · $
              {Math.round(e.ciudad.tarifaDescuento)} estudiante y adulto mayor. Taxi desde $
              {Math.round(e.ciudad.taxiBanderazo)} según la zona.
            </Text>
          )}
          {e.rutas.map((r, i) => (
            <TarjetaRuta
              key={`${r.titulo}-${i}`}
              r={r}
              precioHotel={e.hotelElegido!.precioEstimado}
              activa={e.rutaActiva === i || (e.rutaActiva === null && e.rutaSeleccionada === i)}
              seleccionar={() => motor.seleccionarRuta(i)}
              iniciar={() => void motor.iniciarNav(i)}
            />
          ))}
        </View>
      )}

      {e.hoteles.length > 0 && (
        <View style={{ gap: 8 }}>
          <View style={estilos.filaEncabezado}>
            <Text style={estilos.tituloSeccion}>Alojamiento cercano ({e.hoteles.length})</Text>
            <View style={estilos.filaChips}>
              {(
                [
                  ['DISTANCIA', 'Cerca'],
                  ['PRECIO', '$'],
                  ['VALORACION', '★'],
                ] as Array<[Orden, string]>
              ).map(([o, et]) => (
                <Chip
                  key={o}
                  activo={e.orden === o}
                  texto={et}
                  onPress={() => motor.ordenar(o)}
                />
              ))}
            </View>
          </View>
          {e.hoteles.map((h) => (
            <TarjetaHotel
              key={h.id}
              h={h}
              elegido={e.hotelElegido?.id === h.id}
              onClick={() => void motor.elegirHotel(h.id)}
            />
          ))}
          <Text style={estilos.textoNota}>
            Alojamiento y mapa: © colaboradores de OpenStreetMap (ODbL). Precios estimados por
            categoría.
          </Text>
        </View>
      )}
    </ScrollView>
  );
}

const TEXTOS_RIESGO: Record<string, string> = {
  ALTO: AMBAR_SUAVE,
  MEDIO: AMBAR_SUAVE,
  BAJO: SALVIA,
  DESCONOCIDO: TEXTO_SECUNDARIO,
};

// =================== MAPA OSM (TELÉFONO) ===================

function TarjetaMapaTelefono({
  ubicacion,
  rumboFlecha,
  hoteles,
  hotelElegido,
  ruta,
  alElegirHotel,
}: {
  ubicacion: Punto | null;
  rumboFlecha: number;
  hoteles: Hotel[];
  hotelElegido: Hotel | null;
  ruta: Ruta | null;
  alElegirHotel: (h: Hotel) => void;
}) {
  const [zoomAjuste, setZoomAjuste] = useState(0);
  const [layout, setLayout] = useState({ w: 340, h: 230 });

  const puntosEncuadre = useMemo(() => {
    const pts: Punto[] = [];
    if (ubicacion) pts.push(ubicacion);
    if (hotelElegido) {
      pts.push(hotelElegido.punto);
      for (const t of ruta?.tramos ?? []) {
        const tr =
          t.trazo && t.trazo.length > 0
            ? t.trazo
            : ([t.desdePunto, t.hastaPunto].filter(Boolean) as Punto[]);
        pts.push(...tr);
      }
    } else {
      pts.push(...hoteles.slice(0, 6).map((h) => h.punto));
    }
    return pts.length > 0 ? pts : [Ciudades.SLRC.centro];
  }, [ubicacion, hotelElegido, ruta, hoteles]);

  const { z, cx, cy } = useMemo(() => {
    let minLat = Infinity;
    let maxLat = -Infinity;
    let minLon = Infinity;
    let maxLon = -Infinity;
    for (const p of puntosEncuadre) {
      if (p.lat < minLat) minLat = p.lat;
      if (p.lat > maxLat) maxLat = p.lat;
      if (p.lon < minLon) minLon = p.lon;
      if (p.lon > maxLon) maxLon = p.lon;
    }
    const centro = { lat: (minLat + maxLat) / 2.0, lon: (minLon + maxLon) / 2.0 };
    const span = Math.max(maxLat - minLat, maxLon - minLon);
    const zoomBase =
      span > 0.12 ? 11 : span > 0.06 ? 12 : span > 0.03 ? 13 : span > 0.014 ? 14 : 15;
    const zoom = Math.max(11, Math.min(17, zoomBase + zoomAjuste));
    return {
      z: zoom,
      cx: lonAX(centro.lon, zoom),
      cy: latAY(centro.lat, zoom),
    };
  }, [puntosEncuadre, zoomAjuste]);

  const aPantalla = (p: Punto) => ({
    x: layout.w / 2 + (lonAX(p.lon, z) - cx) * 256,
    y: layout.h / 2 + (latAY(p.lat, z) - cy) * 256,
  });

  const tx0 = Math.floor(cx);
  const ty0 = Math.floor(cy);
  const maxTile = (1 << z) - 1;
  const teselas: Array<{ key: string; url: string; left: number; top: number }> = [];
  for (let dx = -1; dx <= 1; dx++) {
    for (let dy = -1; dy <= 1; dy++) {
      const tx = Math.max(0, Math.min(maxTile, tx0 + dx));
      const ty = Math.max(0, Math.min(maxTile, ty0 + dy));
      teselas.push({
        key: `${z}/${tx}/${ty}/${dx}/${dy}`,
        url: `https://tile.openstreetmap.org/${z}/${tx}/${ty}.png`,
        left: Math.round(layout.w / 2 + (tx0 + dx - cx) * 256),
        top: Math.round(layout.h / 2 + (ty0 + dy - cy) * 256),
      });
    }
  }

  return (
    <View style={estilos.tarjeta}>
      <View style={estilos.filaEncabezado}>
        <Text style={[estilos.tituloTarjeta, { flex: 1 }]}>
          {hotelElegido ? `Croquis hacia ${hotelElegido.nombre}` : 'Mapa de ubicación y hoteles'}
        </Text>
        <View style={{ flexDirection: 'row', gap: 6 }}>
          <Pressable
            style={estilos.botonZoom}
            onPress={() => setZoomAjuste((prev) => Math.max(-3, prev - 1))}
          >
            <Text style={estilos.textoZoom}>−</Text>
          </Pressable>
          <Pressable
            style={estilos.botonZoom}
            onPress={() => setZoomAjuste((prev) => Math.min(3, prev + 1))}
          >
            <Text style={estilos.textoZoom}>+</Text>
          </Pressable>
        </View>
      </View>

      <View
        style={estilos.cajaMapa}
        onLayout={(ev) => {
          const { width, height } = ev.nativeEvent.layout;
          if (width > 0 && height > 0) setLayout({ w: width, h: height });
        }}
      >
        {teselas.map((t) => (
          <Image
            key={t.key}
            source={{ uri: t.url }}
            style={{
              position: 'absolute',
              left: t.left,
              top: t.top,
              width: 256,
              height: 256,
              opacity: 0.92,
            }}
          />
        ))}

        <Svg width={layout.w} height={layout.h} style={StyleSheet.absoluteFill}>
          {/* Cuadrícula suave de respaldo */}
          {[48, 96, 144, 192, 240, 288].map((pos) => (
            <React.Fragment key={pos}>
              <Line x1={pos} y1={0} x2={pos} y2={layout.h} stroke="#CBD5E1" strokeWidth={0.5} />
              <Line x1={0} y1={pos} x2={layout.w} y2={pos} stroke="#CBD5E1" strokeWidth={0.5} />
            </React.Fragment>
          ))}

          {/* Trazos de la ruta */}
          {(ruta?.tramos ?? []).map((t, idx) => {
            const pts =
              t.trazo && t.trazo.length > 0
                ? t.trazo
                : ([t.desdePunto, t.hastaPunto].filter(Boolean) as Punto[]);
            if (pts.length < 2) return null;
            const d = pts
              .map((pt, i) => {
                const o = aPantalla(pt);
                return `${i === 0 ? 'M' : 'L'} ${o.x.toFixed(1)} ${o.y.toFixed(1)}`;
              })
              .join(' ');
            const color =
              t.modo === 'CAMINAR'
                ? SALVIA
                : t.modo === 'TAXI'
                  ? '#6D597A'
                  : AZUL_SERENO;
            return (
              <React.Fragment key={`tramo-${idx}`}>
                <Path
                  d={d}
                  stroke="rgba(255,255,255,0.85)"
                  strokeWidth={8}
                  fill="none"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <Path
                  d={d}
                  stroke={color}
                  strokeWidth={4.5}
                  strokeDasharray={t.modo === 'CAMINAR' ? '8,6' : undefined}
                  fill="none"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </React.Fragment>
            );
          })}

          {/* Hoteles */}
          {hoteles.slice(0, 12).map((h) => {
            const pos = aPantalla(h.punto);
            const esElegido = h.id === hotelElegido?.id;
            return (
              <React.Fragment key={h.id}>
                <Circle
                  cx={pos.x}
                  cy={pos.y}
                  r={esElegido ? 9.5 : 7}
                  fill="#FFFFFF"
                  onPress={() => alElegirHotel(h)}
                />
                <Circle
                  cx={pos.x}
                  cy={pos.y}
                  r={esElegido ? 7 : 5}
                  fill={esElegido ? SALVIA : '#64748B'}
                  onPress={() => alElegirHotel(h)}
                />
              </React.Fragment>
            );
          })}

          {/* Ubicación actual + flecha de orientación */}
          {ubicacion && (() => {
            const pos = aPantalla(ubicacion);
            const rad = ((rumboFlecha - 90) * Math.PI) / 180.0;
            const px = pos.x + Math.cos(rad) * 16;
            const py = pos.y + Math.sin(rad) * 16;
            const izqX = pos.x + Math.cos(rad - 0.5) * 8;
            const izqY = pos.y + Math.sin(rad - 0.5) * 8;
            const derX = pos.x + Math.cos(rad + 0.5) * 8;
            const derY = pos.y + Math.sin(rad + 0.5) * 8;
            return (
              <React.Fragment>
                <Circle cx={pos.x} cy={pos.y} r={14} fill="rgba(58,110,165,0.22)" />
                <Polygon
                  points={`${px},${py} ${izqX},${izqY} ${derX},${derY}`}
                  fill={AZUL_SERENO}
                />
                <Circle cx={pos.x} cy={pos.y} r={7} fill="#FFFFFF" />
                <Circle cx={pos.x} cy={pos.y} r={5} fill={AZUL_SERENO} />
              </React.Fragment>
            );
          })()}
        </Svg>
      </View>

      <View style={[estilos.filaChips, { marginTop: 6, flexWrap: 'wrap' }]}>
        <Text style={estilos.textoLeyenda}>🔵 Tu posición</Text>
        <Text style={estilos.textoLeyenda}>🟢 Hotel destino</Text>
        <Text style={estilos.textoLeyenda}>🟦 Transporte</Text>
        <Text style={estilos.textoLeyenda}>🟩 Caminar</Text>
      </View>
    </View>
  );
}

function TarjetaHotel({
  h,
  elegido,
  onClick,
}: {
  h: Hotel;
  elegido: boolean;
  onClick: () => void;
}) {
  const detalles = [h.direccion, h.telefono].filter(Boolean).join(' · ');
  return (
    <Pressable
      onPress={onClick}
      style={[
        estilos.tarjeta,
        elegido && { backgroundColor: SALVIA_FONDO, borderColor: SALVIA },
      ]}
    >
      <View style={estilos.filaEncabezado}>
        <Text style={[estilos.tituloTarjeta, { flex: 1 }]}>{h.nombre}</Text>
        <Text style={{ color: SALVIA, fontWeight: '700', fontSize: 13 }}>
          {Geo.textoDistancia(h.distanciaM)}
        </Text>
      </View>
      <Text style={estilos.textoPrincipal}>
        {NOMBRE_TIPO[h.tipo] ?? h.tipo} · ≈ ${h.precioEstimado}/noche
        {h.estrellas > 0 ? ` · ${'★'.repeat(h.estrellas)}` : ''}
      </Text>
      {detalles !== '' && <Text style={estilos.textoNota}>{detalles}</Text>}
    </Pressable>
  );
}

function TarjetaRuta({
  r,
  precioHotel,
  activa,
  seleccionar,
  iniciar,
}: {
  r: Ruta;
  precioHotel: number;
  activa: boolean;
  seleccionar: () => void;
  iniciar: () => void;
}) {
  return (
    <Pressable
      onPress={seleccionar}
      style={[
        estilos.tarjeta,
        activa && { backgroundColor: AZUL_FONDO, borderColor: AZUL_SERENO },
      ]}
    >
      <View style={estilos.filaEncabezado}>
        <View style={{ flex: 1 }}>
          <Text style={estilos.tituloTarjeta}>
            {r.minutos} min · ${r.costo.toFixed(2)} · {r.transbordos} transbordo(s)
          </Text>
          <Text style={estilos.textoSecundario}>{r.titulo}</Text>
        </View>
        <Pressable style={estilos.botonIr} onPress={iniciar}>
          <Text style={{ color: '#FFFFFF', fontWeight: '700' }}>Guiar</Text>
        </Pressable>
      </View>

      {r.tramos.map((t, idx) => {
        const icono =
          t.modo === 'CAMINAR'
            ? '🚶'
            : t.modo === 'TAXI'
              ? '🚕'
              : t.modo === 'METRO'
                ? '🚇'
                : t.modo === 'CABLEBUS'
                  ? '🚡'
                  : t.modo === 'TREN' || t.modo === 'TREN_LIGERO'
                    ? '🚆'
                    : '🚌';
        const desc =
          t.modo === 'CAMINAR'
            ? `Camina ${t.minutos} min a ${t.hasta}`
            : t.modo === 'TAXI'
              ? `Taxi/app ${t.minutos} min (≈ $${Math.round(t.costo)}${t.linea ? ` · ${t.linea}` : ''})`
              : `${t.linea}: ${t.desde} → ${t.hasta} (${t.minutos} min, $${t.costo.toFixed(2)})`;
        return (
          <Text key={idx} style={estilos.textoPequeno}>
            {icono} {desc}
          </Text>
        );
      })}
      <Text style={[estilos.textoPequeno, { color: SALVIA, fontWeight: '600', marginTop: 4 }]}>
        Total estimado con 1 noche ≈ ${Math.round(r.costo + precioHotel)}
      </Text>
    </Pressable>
  );
}

// =================== AVISOS Y PREFERENCIAS ===================

function PantallaPreferencias({ e, motor }: { e: EstadoApp; motor: Motor }) {
  const s = motor.seguridad;
  const [quieto, setQuieto] = useState(s.detectarQuieto);
  const [minutos, setMinutos] = useState(s.minutosQuieto);
  const [riesgo, setRiesgo] = useState(s.avisosRiesgo);
  const [historial, setHistorial] = useState(s.puntosHistorial());

  return (
    <ScrollView contentContainerStyle={estilos.listaContenido}>
      <Text style={estilos.tituloPrincipal}>Avisos de viaje</Text>
      <Text style={estilos.subtitulo}>
        Notificaciones discretas en el reloj y el teléfono durante tu trayecto
      </Text>

      <View style={estilos.tarjeta}>
        <View style={estilos.filaEncabezado}>
          <Text style={[estilos.textoPrincipal, { flex: 1, fontWeight: '600' }]}>
            Aviso por inactividad en ruta
          </Text>
          <Switch
            value={quieto}
            trackColor={{ false: '#CBD5E1', true: '#94D2C5' }}
            thumbColor={quieto ? SALVIA : '#F8FAFC'}
            onValueChange={(v) => {
              setQuieto(v);
              s.detectarQuieto = v;
            }}
          />
        </View>
        <Text style={estilos.textoSecundario}>
          Te pregunta discretamente en el reloj si llevas {minutos} min sin avanzar durante una
          navegación.
        </Text>
        <View style={[estilos.filaChips, { marginTop: 8 }]}>
          {[5, 10, 15, 20, 30].map((m) => (
            <Chip
              key={m}
              activo={minutos === m}
              texto={`${m} min`}
              onPress={() => {
                setMinutos(m);
                s.minutosQuieto = m;
              }}
            />
          ))}
        </View>

        <View style={[estilos.filaEncabezado, { marginTop: 14 }]}>
          <Text style={[estilos.textoPrincipal, { flex: 1, fontWeight: '600' }]}>
            Avisos orientativos de zona
          </Text>
          <Switch
            value={riesgo}
            trackColor={{ false: '#CBD5E1', true: '#94D2C5' }}
            thumbColor={riesgo ? SALVIA : '#F8FAFC'}
            onValueChange={(v) => {
              setRiesgo(v);
              s.avisosRiesgo = v;
            }}
          />
        </View>
      </View>

      <Pressable style={estilos.botonSecundario} onPress={() => motor.reportarZona()}>
        <Text style={[estilos.textoBotonSecundario, { textAlign: 'center' }]}>
          Marcar mi ubicación actual como zona de precaución
        </Text>
      </Pressable>

      <View style={estilos.tarjeta}>
        <Text style={estilos.textoPrincipal}>
          Puntos de recorrido guardados localmente: {historial}
        </Text>
        <Pressable
          style={{ marginTop: 6 }}
          onPress={() => {
            s.borrarHistorial();
            setHistorial(0);
          }}
        >
          <Text style={{ color: AZUL_SERENO, fontWeight: '600' }}>Limpiar recorrido local</Text>
        </Pressable>
      </View>

      <Text style={estilos.textoNota}>
        Datos de precaución en CDMX: carpetas FGJ {ANIO_DATOS} (datos.cdmx.gob.mx, coordenadas
        aproximadas). En otras ciudades se consideran tus propias notas y el horario nocturno.
      </Text>
    </ScrollView>
  );
}

// =================== RELOJ ===================

function PantallaReloj({
  e,
  motor,
  conectado,
  puenteActivo,
}: {
  e: EstadoApp;
  motor: Motor;
  conectado: boolean;
  puenteActivo: boolean;
}) {
  const [hostInput, setHostInput] = useState(motor.enlace.hostActual);

  const resumenReloj = e.nav
    ? `Navegación: ${flechaTexto(e.nav.flechaGrados)} ${Geo.textoDistancia(e.nav.metrosAlGiro)} · ${e.nav.calle}`
    : e.rutas.length > 0
      ? `${e.rutas.length} rutas a ${e.hotelElegido?.nombre ?? ''}`
      : e.hoteles.length > 0
        ? `${Math.min(8, e.hoteles.length)} hoteles`
        : 'Inicio (esperando búsqueda)';

  return (
    <ScrollView contentContainerStyle={estilos.listaContenido}>
      <Text style={estilos.tituloPrincipal}>Reloj (Wear OS)</Text>
      <Text style={estilos.subtitulo}>
        Sincronización con el emulador del smartwatch en tu laptop
      </Text>

      <View style={estilos.tarjeta}>
        <Text
          style={{
            color: conectado ? SALVIA : AMBAR_SUAVE,
            fontWeight: '700',
            fontSize: 16,
          }}
        >
          {conectado ? '● Reloj conectado' : '○ Reloj no detectado aún'}
        </Text>
        <Text
          style={{
            color: puenteActivo ? AZUL_SERENO : AMBAR_SUAVE,
            fontSize: 13,
            marginTop: 2,
          }}
        >
          {puenteActivo
            ? `● Puente activo (${motor.enlace.urlPuente})`
            : `○ Puente no detectado en ${motor.enlace.urlPuente} (corre: npm run puente)`}
        </Text>
        <Text style={[estilos.textoNota, { marginTop: 4 }]}>
          En la laptop: HTTP {PUERTO_PUENTE_HTTP} (Expo) ↔ TCP {PUERTO_TELEFONO_TCP}/
          {PUERTO_RELOJ_TCP} (Emulador Wear OS). En el reloj puedes girar la brújula con ↺ / ↻ o
          arrastrando el croquis.
        </Text>

        <View style={[estilos.filaBotones, { marginTop: 8 }]}>
          <TextInput
            value={hostInput}
            onChangeText={setHostInput}
            placeholder="IP de tu laptop (ej. 192.168.1.50)"
            placeholderTextColor="#8B98A9"
            style={[estilos.campoTexto, { flex: 1, minHeight: 40, paddingVertical: 6 }]}
          />
          <Pressable
            style={estilos.botonSecundario}
            onPress={() => motor.enlace.cambiarHost(hostInput)}
          >
            <Text style={estilos.textoBotonSecundario}>Conectar IP</Text>
          </Pressable>
        </View>
      </View>

      <View style={estilos.tarjeta}>
        <Text style={estilos.tituloTarjeta}>Estado actual en el reloj</Text>
        <Text style={estilos.textoPrincipal}>{resumenReloj}</Text>
        {e.soloVibracion && (
          <Text style={{ color: SALVIA, marginTop: 4 }}>
            Modo discreto (solo vibración) activo en el reloj
          </Text>
        )}
      </View>

      <Text style={estilos.tituloSeccion}>Actividad reciente</Text>
      {e.bitacora.length === 0 ? (
        <Text style={estilos.textoNota}>Sin acciones desde el reloj todavía.</Text>
      ) : (
        e.bitacora.map((linea, idx) => (
          <Text key={idx} style={estilos.textoNota}>
            {linea}
          </Text>
        ))
      )}
    </ScrollView>
  );
}

function Chip({
  activo,
  texto,
  onPress,
}: {
  activo: boolean;
  texto: string;
  onPress: () => void;
}) {
  return (
    <Pressable onPress={onPress} style={[estilos.chip, activo && estilos.chipActivo]}>
      <Text style={[estilos.textoChip, activo && { color: SALVIA, fontWeight: '700' }]}>
        {texto}
      </Text>
    </Pressable>
  );
}

const estilos = StyleSheet.create({
  contenedor: {
    flex: 1,
    backgroundColor: FONDO,
    paddingTop: Platform.OS === 'android' ? 28 : 0,
  },
  cuerpo: {
    flex: 1,
  },
  listaContenido: {
    padding: 16,
    gap: 10,
    paddingBottom: 28,
  },
  tituloPrincipal: {
    color: TEXTO_PRINCIPAL,
    fontSize: 25,
    fontWeight: '700',
  },
  subtitulo: {
    color: TEXTO_SECUNDARIO,
    fontSize: 13,
  },
  etiquetaSeccion: {
    color: TEXTO_SECUNDARIO,
    fontSize: 12,
    fontWeight: '600',
  },
  tituloSeccion: {
    color: TEXTO_PRINCIPAL,
    fontSize: 17,
    fontWeight: '700',
  },
  tituloTarjeta: {
    color: TEXTO_PRINCIPAL,
    fontSize: 15,
    fontWeight: '700',
  },
  textoPrincipal: {
    color: TEXTO_PRINCIPAL,
    fontSize: 14,
  },
  textoSecundario: {
    color: TEXTO_SECUNDARIO,
    fontSize: 13,
  },
  textoPequeno: {
    color: TEXTO_PRINCIPAL,
    fontSize: 13,
  },
  textoNota: {
    color: TEXTO_SECUNDARIO,
    fontSize: 12,
  },
  textoLeyenda: {
    color: TEXTO_SECUNDARIO,
    fontSize: 11,
  },
  insigniaBrujula: {
    backgroundColor: SALVIA_FONDO,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#B8DFD6',
  },
  textoInsigniaBrujula: {
    color: SALVIA,
    fontWeight: '700',
    fontSize: 12,
  },
  insigniaDistancia: {
    color: SALVIA,
    fontWeight: '700',
    fontSize: 16,
  },
  filaChips: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  filaBotones: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  filaEncabezado: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 18,
    backgroundColor: SUPERFICIE_SUAVE,
    borderWidth: 1,
    borderColor: BORDE,
  },
  chipActivo: {
    backgroundColor: SALVIA_FONDO,
    borderColor: SALVIA,
  },
  textoChip: {
    color: TEXTO_PRINCIPAL,
    fontSize: 13,
  },
  campoTexto: {
    backgroundColor: '#F8FAFC',
    color: TEXTO_PRINCIPAL,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: BORDE,
    paddingHorizontal: 12,
    paddingVertical: 10,
    minHeight: 50,
    fontSize: 14,
  },
  botonPrimario: {
    backgroundColor: SALVIA,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 18,
  },
  textoBotonPrimario: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 13,
  },
  botonSecundario: {
    backgroundColor: SUPERFICIE_SUAVE,
    borderWidth: 1,
    borderColor: BORDE,
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: 18,
  },
  textoBotonSecundario: {
    color: AZUL_SERENO,
    fontWeight: '600',
    fontSize: 13,
  },
  botonSugerencia: {
    backgroundColor: '#F8FAFC',
    borderWidth: 1,
    borderColor: BORDE,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 14,
  },
  textoSugerencia: {
    color: TEXTO_SECUNDARIO,
    fontSize: 12,
  },
  botonIr: {
    backgroundColor: SALVIA,
    paddingHorizontal: 15,
    paddingVertical: 8,
    borderRadius: 16,
  },
  cajaMensaje: {
    backgroundColor: AMBAR_FONDO,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: '#F2DEA8',
  },
  textoMensaje: {
    color: AMBAR_SUAVE,
    fontSize: 13,
  },
  tarjeta: {
    backgroundColor: SUPERFICIE,
    padding: 12,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: BORDE,
    gap: 4,
  },
  tarjetaNav: {
    backgroundColor: SALVIA_FONDO,
    padding: 14,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#A9D6CB',
    gap: 4,
  },
  cajaMapa: {
    width: '100%',
    height: 230,
    borderRadius: 10,
    overflow: 'hidden',
    backgroundColor: '#E2E8F0',
    marginTop: 6,
    borderWidth: 1,
    borderColor: BORDE,
  },
  botonZoom: {
    backgroundColor: SUPERFICIE_SUAVE,
    width: 30,
    height: 28,
    borderRadius: 6,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: BORDE,
  },
  textoZoom: {
    color: TEXTO_PRINCIPAL,
    fontSize: 16,
    fontWeight: '700',
  },
  barraInferior: {
    flexDirection: 'row',
    backgroundColor: SUPERFICIE,
    borderTopWidth: 1,
    borderTopColor: BORDE,
    paddingVertical: 6,
  },
  itemBarra: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 4,
  },
  itemBarraActivo: {
    backgroundColor: SALVIA_FONDO,
    borderRadius: 12,
    marginHorizontal: 8,
  },
  iconoBarra: {
    fontSize: 18,
  },
  textoBarra: {
    color: TEXTO_SECUNDARIO,
    fontSize: 12,
    marginTop: 2,
  },
  modalFondo: {
    flex: 1,
    backgroundColor: 'rgba(15,23,42,0.45)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  modalCaja: {
    backgroundColor: SUPERFICIE,
    borderRadius: 14,
    padding: 18,
    width: '100%',
    maxWidth: 360,
    borderWidth: 1,
    borderColor: BORDE,
    gap: 10,
  },
  modalTitulo: {
    color: TEXTO_PRINCIPAL,
    fontSize: 18,
    fontWeight: '700',
  },
  modalTexto: {
    color: TEXTO_SECUNDARIO,
    fontSize: 14,
  },
  modalBotones: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    marginTop: 6,
  },
});
