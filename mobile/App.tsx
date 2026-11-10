import * as Location from 'expo-location';
import { StatusBar } from 'expo-status-bar';
import React, { useEffect, useMemo, useRef, useState } from 'react';
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
import Svg, { Circle, Line, Path } from 'react-native-svg';
import { Geo } from './src/core/Geo.ts';
import type { Hotel, Orden, Punto, Ruta } from './src/core/Modelos.ts';
import { Ciudades } from './src/datos/Ciudades.ts';
import { NOMBRE_TIPO } from './src/datos/Osm.ts';
import { ANIO_DATOS } from './src/datos/Riesgo.ts';
import { PUERTO_PUENTE_HTTP, PUERTO_RELOJ_TCP, PUERTO_TELEFONO_TCP } from './src/logica/EnlaceExpo.ts';
import { type EstadoApp, Motor } from './src/logica/Motor.ts';

const AMBAR = '#FCA311';
const ROJO = '#E5484D';
const VERDE = '#2E9E5B';
const AZUL_RUTA = '#00B8F0';
const FONDO = '#0E131F';
const SUPERFICIE = '#182032';

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
        subPos = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.Balanced,
            timeInterval: 3000,
            distanceInterval: 5,
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
  }, [motor]);

  const alerta = estado.alerta;

  return (
    <SafeAreaView style={estilos.contenedor}>
      <StatusBar style="light" />
      <View style={estilos.cuerpo}>
        {pestana === 0 && <PantallaViaje e={estado} motor={motor} />}
        {pestana === 1 && <PantallaSeguridad e={estado} motor={motor} />}
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
          { ic: '🛡', et: 'Seguridad' },
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
              <Text style={[estilos.textoBarra, activo && { color: AMBAR, fontWeight: '700' }]}>
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
              {alerta?.tipo === 'RIESGO' ? '⚠ Aviso' : 'Sereno'}
            </Text>
            <Text style={estilos.modalTexto}>{alerta?.texto}</Text>
            <View style={estilos.modalBotones}>
              {alerta?.pideRespuesta && (
                <Pressable style={estilos.botonModalPeligro} onPress={() => motor.panico()}>
                  <Text style={{ color: ROJO, fontWeight: '700' }}>Llamar 911</Text>
                </Pressable>
              )}
              <Pressable
                style={estilos.botonModalOk}
                onPress={() => (alerta?.pideRespuesta ? motor.estoyBien() : motor.cerrarAlerta())}
              >
                <Text style={{ color: AMBAR, fontWeight: '700' }}>
                  {alerta?.pideRespuesta ? 'Estoy bien' : 'OK'}
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

function PantallaViaje({ e, motor }: { e: EstadoApp; motor: Motor }) {
  const [texto, setTexto] = useState('Necesito hotel barato cerca del centro y transporte público');
  const rutaVisible =
    e.rutaActiva !== null ? e.rutas[e.rutaActiva] : e.rutas[e.rutaSeleccionada];

  const colorRiesgo =
    e.riesgo?.nivel === 'ALTO'
      ? ROJO
      : e.riesgo?.nivel === 'MEDIO'
        ? AMBAR
        : e.riesgo?.nivel === 'BAJO'
          ? VERDE
          : '#9CA3AF';

  return (
    <ScrollView contentContainerStyle={estilos.listaContenido}>
      <Text style={estilos.tituloPrincipal}>Sereno</Text>
      <Text style={estilos.subtitulo}>Compañero de viaje discreto (Expo + Wear OS)</Text>

      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginTop: 8 }}>
        <View style={estilos.filaChips}>
          <Chip
            activo={e.fuenteUbicacion === 'gps'}
            texto="📍 GPS"
            onPress={() => motor.usarFuente('gps')}
          />
          {Ciudades.todas.map((c) => (
            <Chip
              key={c.id}
              activo={e.fuenteUbicacion === c.id}
              texto={`${c.nombre} (${c.tamano.toLowerCase()})`}
              onPress={() => motor.usarFuente(c.id)}
            />
          ))}
        </View>
      </ScrollView>

      <Text style={estilos.textoGris}>
        {e.ciudad?.nombre ?? 'Ciudad no detectada'}
        {e.ubicacion ? ` · ${e.ubicacion.lat.toFixed(4)}, ${e.ubicacion.lon.toFixed(4)}` : ''}
      </Text>
      {e.riesgo && (
        <Text style={[estilos.textoPequeno, { color: colorRiesgo }]}>
          Zona: {e.riesgo.nivel.toLowerCase()} — {e.riesgo.motivo}
        </Text>
      )}

      <TextInput
        value={texto}
        onChangeText={setTexto}
        placeholder="¿Qué necesitas? (puedes usar el micrófono del teclado)"
        placeholderTextColor="#6B7280"
        multiline
        style={estilos.campoTexto}
      />

      <View style={estilos.filaBotones}>
        <Pressable
          style={[estilos.botonPrimario, e.buscando && { opacity: 0.6 }]}
          disabled={e.buscando}
          onPress={() => void motor.buscar(texto)}
        >
          <Text style={estilos.textoBotonPrimario}>Buscar</Text>
        </Pressable>
      </View>

      <ScrollView horizontal showsHorizontalScrollIndicator={false}>
        <View style={estilos.filaChips}>
          {[
            'Hostal barato',
            'Hotel mejor valorado cerca',
            'Motel menos de 800',
            'Hotel cerca del centro',
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

      {(e.buscando || e.calculandoRutas) && (
        <ActivityIndicator color={AMBAR} style={{ marginVertical: 6 }} />
      )}
      {e.mensaje && <Text style={[estilos.textoPequeno, { color: AMBAR }]}>{e.mensaje}</Text>}

      {(e.ubicacion || e.hoteles.length > 0 || e.rutas.length > 0) && (
        <TarjetaMapaTelefono
          ubicacion={e.ubicacion ?? e.ciudad?.centro ?? null}
          hoteles={e.hoteles}
          hotelElegido={e.hotelElegido}
          ruta={rutaVisible ?? null}
          alElegirHotel={(h) => void motor.elegirHotel(h.id)}
        />
      )}

      {e.nav && (
        <View style={estilos.tarjetaNav}>
          <Text style={estilos.tituloTarjeta}>Navegando a {e.nav.destino}</Text>
          <Text style={estilos.textoBlanco}>
            {flechaTexto(e.nav.flechaGrados)}  {Geo.textoDistancia(e.nav.metrosAlGiro)} →{' '}
            {e.nav.giro.toLowerCase().replace('_', ' ')} en {e.nav.calle}
          </Text>
          {e.nav.indicacion && <Text style={{ color: AMBAR }}>{e.nav.indicacion}</Text>}
          <Text style={estilos.textoGris}>
            Faltan {Geo.textoDistancia(e.nav.metrosRestantes)} · ~{e.nav.minutosRestantes} min
          </Text>
          <View style={[estilos.filaBotones, { marginTop: 8 }]}>
            <Pressable
              style={estilos.botonSecundario}
              onPress={() => motor.simular(!e.simulando)}
            >
              <Text style={estilos.textoBotonSecundario}>
                {e.simulando ? 'Pausar simulación' : 'Simular caminata'}
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
          <Text style={estilos.tituloSeccion}>Cómo llegar a {e.hotelElegido.nombre}</Text>
          {e.ciudad?.tarifaDescuento !== undefined && (
            <Text style={estilos.textoNota}>
              Combi en {e.ciudad.nombre}: ${Math.round(e.ciudad.tarifas.MICRO ?? 14)} general · $
              {Math.round(e.ciudad.tarifaDescuento)} estudiante y adulto mayor (parada en cualquier
              punto de la ruta). Taxi desde ${Math.round(e.ciudad.taxiBanderazo)} según la zona.
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
            <Text style={estilos.tituloSeccion}>Alojamiento</Text>
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
            Alojamiento y mapa: © colaboradores de OpenStreetMap (ODbL). Precios ESTIMADOS por tipo
            y estrellas; no hay API gratuita de precios reales. Confirma al llegar.
          </Text>
        </View>
      )}
    </ScrollView>
  );
}

// =================== MAPA OSM (TELÉFONO) ===================

function TarjetaMapaTelefono({
  ubicacion,
  hoteles,
  hotelElegido,
  ruta,
  alElegirHotel,
}: {
  ubicacion: Punto | null;
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
    <View style={estilos.tarjetaMapa}>
      <View style={estilos.filaEncabezado}>
        <Text style={[estilos.tituloTarjeta, { flex: 1 }]}>
          {hotelElegido ? `Mapa: ruta a ${hotelElegido.nombre}` : 'Mapa de alojamiento y rutas'}
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
              opacity: 0.88,
            }}
          />
        ))}

        <Svg width={layout.w} height={layout.h} style={StyleSheet.absoluteFill}>
          {/* Cuadrícula de respaldo */}
          {[48, 96, 144, 192, 240, 288].map((pos) => (
            <React.Fragment key={pos}>
              <Line x1={pos} y1={0} x2={pos} y2={layout.h} stroke="#28344E" strokeWidth={0.5} />
              <Line x1={0} y1={pos} x2={layout.w} y2={pos} stroke="#28344E" strokeWidth={0.5} />
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
                ? AMBAR
                : t.modo === 'TAXI'
                  ? '#A855F7'
                  : t.modo === 'METRO'
                    ? '#FF6B35'
                    : AZUL_RUTA;
            return (
              <React.Fragment key={`tramo-${idx}`}>
                <Path
                  d={d}
                  stroke="rgba(0,0,0,0.65)"
                  strokeWidth={7}
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
                  r={esElegido ? 9 : 6.5}
                  fill="#000000"
                  onPress={() => alElegirHotel(h)}
                />
                <Circle
                  cx={pos.x}
                  cy={pos.y}
                  r={esElegido ? 7 : 4.5}
                  fill={esElegido ? VERDE : AMBAR}
                  onPress={() => alElegirHotel(h)}
                />
              </React.Fragment>
            );
          })}

          {/* Ubicación actual */}
          {ubicacion && (
            <React.Fragment>
              <Circle
                cx={aPantalla(ubicacion).x}
                cy={aPantalla(ubicacion).y}
                r={13}
                fill="rgba(37,99,235,0.35)"
              />
              <Circle
                cx={aPantalla(ubicacion).x}
                cy={aPantalla(ubicacion).y}
                r={6.5}
                fill="#FFFFFF"
              />
              <Circle
                cx={aPantalla(ubicacion).x}
                cy={aPantalla(ubicacion).y}
                r={4.5}
                fill="#2563EB"
              />
            </React.Fragment>
          )}
        </Svg>
      </View>

      <View style={[estilos.filaChips, { marginTop: 6, flexWrap: 'wrap' }]}>
        <Text style={estilos.textoLeyenda}>🔵 Tu ubicación</Text>
        <Text style={estilos.textoLeyenda}>🟢 Destino</Text>
        <Text style={estilos.textoLeyenda}>🟦 Combi / transporte</Text>
        <Text style={estilos.textoLeyenda}>🟨 Caminar</Text>
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
      style={[estilos.tarjeta, elegido && { backgroundColor: '#3A2E10', borderColor: AMBAR }]}
    >
      <Text style={estilos.tituloTarjeta}>{h.nombre}</Text>
      <Text style={estilos.textoBlanco}>
        {NOMBRE_TIPO[h.tipo] ?? h.tipo} · ≈ ${h.precioEstimado}/noche ·{' '}
        {Geo.textoDistancia(h.distanciaM)}
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
      style={[estilos.tarjeta, activa && { backgroundColor: '#1F3A2A', borderColor: VERDE }]}
    >
      <View style={estilos.filaEncabezado}>
        <View style={{ flex: 1 }}>
          <Text style={estilos.tituloTarjeta}>
            {r.minutos} min · ${r.costo.toFixed(2)} · {r.transbordos} transbordo(s)
          </Text>
          <Text style={estilos.textoGris}>{r.titulo}</Text>
        </View>
        <Pressable style={estilos.botonIr} onPress={iniciar}>
          <Text style={{ color: '#000', fontWeight: '700' }}>Ir</Text>
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
      <Text style={[estilos.textoPequeno, { color: AMBAR, marginTop: 4 }]}>
        Total con 1 noche ≈ ${Math.round(r.costo + precioHotel)}
      </Text>
    </Pressable>
  );
}

// =================== SEGURIDAD ===================

function PantallaSeguridad({ e, motor }: { e: EstadoApp; motor: Motor }) {
  const s = motor.seguridad;
  const [quieto, setQuieto] = useState(s.detectarQuieto);
  const [minutos, setMinutos] = useState(s.minutosQuieto);
  const [riesgo, setRiesgo] = useState(s.avisosRiesgo);
  const [historial, setHistorial] = useState(s.puntosHistorial());

  return (
    <ScrollView contentContainerStyle={estilos.listaContenido}>
      <Text style={estilos.tituloPrincipal}>Seguridad</Text>

      <View style={estilos.tarjeta}>
        <View style={estilos.filaEncabezado}>
          <Text style={[estilos.textoBlanco, { flex: 1 }]}>
            Preguntar si estoy bien al quedarme quieto
          </Text>
          <Switch
            value={quieto}
            onValueChange={(v) => {
              setQuieto(v);
              s.detectarQuieto = v;
            }}
          />
        </View>
        <Text style={estilos.textoGris}>
          Tras {minutos} min sin moverte durante un trayecto (fuera del hotel). Si no respondes en 2
          min se abre llamada al 911.
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

        <View style={[estilos.filaEncabezado, { marginTop: 12 }]}>
          <Text style={[estilos.textoBlanco, { flex: 1 }]}>Avisos de zona de riesgo</Text>
          <Switch
            value={riesgo}
            onValueChange={(v) => {
              setRiesgo(v);
              s.avisosRiesgo = v;
            }}
          />
        </View>
      </View>

      <Pressable style={estilos.botonPanico} onPress={() => motor.panico()}>
        <Text style={{ color: '#FFF', fontWeight: '700', textAlign: 'center' }}>
          SOS · MARCAR 911 (en el reloj: mantener presionado)
        </Text>
      </Pressable>

      <Pressable style={estilos.botonSecundario} onPress={() => motor.reportarZona()}>
        <Text style={[estilos.textoBotonSecundario, { textAlign: 'center' }]}>
          Marcar mi ubicación como zona insegura
        </Text>
      </Pressable>

      <View style={estilos.tarjeta}>
        <Text style={estilos.textoBlanco}>
          Historial de ruta guardado en el teléfono: {historial} puntos
        </Text>
        <Pressable
          style={{ marginTop: 6 }}
          onPress={() => {
            s.borrarHistorial();
            setHistorial(0);
          }}
        >
          <Text style={{ color: AMBAR }}>Borrar historial</Text>
        </Pressable>
      </View>

      <Text style={estilos.textoNota}>
        Riesgo en CDMX: carpetas de investigación FGJ {ANIO_DATOS} (datos.cdmx.gob.mx, coordenadas
        aproximadas). En San Luis R.C. no hay delitos geolocalizados abiertos: se usan tus reportes
        y la hora. Todo es orientativo.
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
        : 'Inicio (sin búsqueda)';

  return (
    <ScrollView contentContainerStyle={estilos.listaContenido}>
      <Text style={estilos.tituloPrincipal}>Reloj (Wear OS)</Text>

      <View style={estilos.tarjeta}>
        <Text style={{ color: conectado ? VERDE : ROJO, fontWeight: '700', fontSize: 16 }}>
          {conectado ? '● Reloj conectado' : '○ Reloj no conectado'}
        </Text>
        <Text style={{ color: puenteActivo ? VERDE : AMBAR, fontSize: 13, marginTop: 2 }}>
          {puenteActivo
            ? `● Puente local activo (${motor.enlace.urlPuente})`
            : `○ Puente local no detectado en ${motor.enlace.urlPuente} (corre: npm run puente)`}
        </Text>
        <Text style={[estilos.textoNota, { marginTop: 4 }]}>
          El puente en la laptop escucha HTTP {PUERTO_PUENTE_HTTP} desde Expo (Android/iOS) y enlaza
          por TCP {PUERTO_TELEFONO_TCP}/{PUERTO_RELOJ_TCP} con el emulador del reloj.
        </Text>

        <View style={[estilos.filaBotones, { marginTop: 8 }]}>
          <TextInput
            value={hostInput}
            onChangeText={setHostInput}
            placeholder="IP de tu laptop (ej. 192.168.1.50)"
            placeholderTextColor="#6B7280"
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
        <Text style={estilos.tituloTarjeta}>Lo que ve el reloj ahora</Text>
        <Text style={estilos.textoBlanco}>{resumenReloj}</Text>
        {e.soloVibracion && (
          <Text style={{ color: AMBAR, marginTop: 4 }}>
            Modo solo vibración activado desde el reloj
          </Text>
        )}
      </View>

      <Text style={estilos.tituloSeccion}>Bitácora</Text>
      {e.bitacora.length === 0 ? (
        <Text style={estilos.textoNota}>Sin comandos del reloj todavía.</Text>
      ) : (
        e.bitacora.map((linea, idx) => (
          <Text key={idx} style={estilos.textoNota}>
            {linea}
          </Text>
        ))
      )}

      <Text style={[estilos.textoNota, { marginTop: 12 }]}>
        Datos: OpenStreetMap (Overpass, Nominatim, teselas), OSRM de FOSSGIS (rutas a pie), datos
        abiertos CDMX y rutas locales de SLRC.
      </Text>
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
      <Text style={[estilos.textoChip, activo && { color: '#000', fontWeight: '700' }]}>
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
    color: '#FFFFFF',
    fontSize: 26,
    fontWeight: '700',
  },
  subtitulo: {
    color: '#9CA3AF',
    fontSize: 13,
  },
  tituloSeccion: {
    color: '#FFFFFF',
    fontSize: 17,
    fontWeight: '700',
  },
  tituloTarjeta: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '700',
  },
  textoBlanco: {
    color: '#E5E7EB',
    fontSize: 14,
  },
  textoGris: {
    color: '#9CA3AF',
    fontSize: 13,
  },
  textoPequeno: {
    color: '#D1D5DB',
    fontSize: 13,
  },
  textoNota: {
    color: '#9CA3AF',
    fontSize: 12,
  },
  textoLeyenda: {
    color: '#D1D5DB',
    fontSize: 11,
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
    paddingVertical: 6,
    borderRadius: 18,
    backgroundColor: SUPERFICIE,
    borderWidth: 1,
    borderColor: '#2E3A52',
  },
  chipActivo: {
    backgroundColor: AMBAR,
    borderColor: AMBAR,
  },
  textoChip: {
    color: '#E5E7EB',
    fontSize: 13,
  },
  campoTexto: {
    backgroundColor: SUPERFICIE,
    color: '#FFFFFF',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#2E3A52',
    paddingHorizontal: 12,
    paddingVertical: 10,
    minHeight: 54,
    fontSize: 14,
  },
  botonPrimario: {
    backgroundColor: AMBAR,
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: 20,
  },
  textoBotonPrimario: {
    color: '#000000',
    fontWeight: '700',
    fontSize: 14,
  },
  botonSecundario: {
    borderWidth: 1,
    borderColor: '#4B5563',
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: 20,
  },
  textoBotonSecundario: {
    color: AMBAR,
    fontWeight: '600',
    fontSize: 13,
  },
  botonSugerencia: {
    borderWidth: 1,
    borderColor: '#374151',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 16,
  },
  textoSugerencia: {
    color: '#D1D5DB',
    fontSize: 12,
  },
  botonIr: {
    backgroundColor: AMBAR,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 18,
  },
  botonPanico: {
    backgroundColor: ROJO,
    paddingVertical: 14,
    paddingHorizontal: 16,
    borderRadius: 22,
  },
  tarjeta: {
    backgroundColor: SUPERFICIE,
    padding: 12,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#263147',
    gap: 4,
  },
  tarjetaNav: {
    backgroundColor: '#1F2A44',
    padding: 14,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#3B4F7D',
    gap: 4,
  },
  tarjetaMapa: {
    backgroundColor: '#141B29',
    padding: 10,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#263147',
  },
  cajaMapa: {
    width: '100%',
    height: 230,
    borderRadius: 10,
    overflow: 'hidden',
    backgroundColor: '#1B2436',
    marginTop: 6,
  },
  botonZoom: {
    backgroundColor: '#263147',
    width: 30,
    height: 28,
    borderRadius: 6,
    alignItems: 'center',
    justifyContent: 'center',
  },
  textoZoom: {
    color: AMBAR,
    fontSize: 16,
    fontWeight: '700',
  },
  barraInferior: {
    flexDirection: 'row',
    backgroundColor: '#121826',
    borderTopWidth: 1,
    borderTopColor: '#222C40',
    paddingVertical: 6,
  },
  itemBarra: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 4,
  },
  itemBarraActivo: {
    backgroundColor: 'rgba(252,163,17,0.1)',
    borderRadius: 12,
    marginHorizontal: 8,
  },
  iconoBarra: {
    fontSize: 18,
  },
  textoBarra: {
    color: '#9CA3AF',
    fontSize: 12,
    marginTop: 2,
  },
  modalFondo: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.65)',
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
    borderColor: '#374151',
    gap: 10,
  },
  modalTitulo: {
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '700',
  },
  modalTexto: {
    color: '#E5E7EB',
    fontSize: 14,
  },
  modalBotones: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 16,
    marginTop: 6,
  },
  botonModalOk: {
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  botonModalPeligro: {
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
});
