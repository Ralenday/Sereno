package com.ralenday.sereno.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.ralenday.sereno.core.Comando
import com.ralenday.sereno.core.Enlace
import com.ralenday.sereno.core.EstadoReloj
import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Giro
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.NavActual
import com.ralenday.sereno.core.Orden
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.TipoAlerta
import com.ralenday.sereno.core.TipoComando
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Paleta suave y descansada para la vista (sin tonos neón ni rojos intensos)
val FondoPizarra = Color(0xFF161D24)
val SuperficiePizarra = Color(0xFF222D38)
val Salvia = Color(0xFF68B69F)
val AzulSereno = Color(0xFF72A8C9)
val ArenaSuave = Color(0xFFD8B98A)
val TextoClaro = Color(0xFFE6EDF3)
val TextoTenue = Color(0xFF9FB0C2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppReloj() }
    }
}

/**
 * Escucha la brújula/sensor de rotación del reloj (o emulador en la laptop) y permite sumar un
 * giro manual en pantalla para simular cambios de orientación con el mouse.
 */
@Composable
fun rememberBrujulaReloj(context: Context): Pair<Float?, (Float) -> Unit> {
    var rumboSensor by remember { mutableStateOf<Float?>(null) }
    var ajusteManual by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matriz = FloatArray(9)
        val orientacion = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matriz, ev.values)
                SensorManager.getOrientation(matriz, orientacion)
                val grados = ((Math.toDegrees(orientacion[0].toDouble()) + 360.0) % 360.0).toFloat()
                rumboSensor = grados
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm?.unregisterListener(listener) }
    }

    val rumboEfectivo = if (rumboSensor != null || ajusteManual != 0f) {
        (((rumboSensor ?: 0f) + ajusteManual) % 360f + 360f) % 360f
    } else null

    return rumboEfectivo to { delta ->
        ajusteManual = if (delta == 0f) 0f else ((ajusteManual + delta) % 360f + 360f) % 360f
    }
}

@Composable
fun AppReloj() {
    val context = LocalContext.current
    val enlace = remember { Enlace.get(context) }
    val vib = remember { Vibracion(context) }
    val estadoState = enlace.estado.collectAsState()
    val conectadoState = enlace.conectado.collectAsState()
    val estado = estadoState.value
    val scope = rememberCoroutineScope()
    val nav = rememberSwipeDismissableNavController()
    val e = estado ?: EstadoReloj()
    val (rumboLocal, girarBrujula) = rememberBrujulaReloj(context)
    val enviar: (Comando) -> Unit = { c -> vib.toque(); scope.launch { enlace.enviar(c) } }

    VibracionesNavegacion(e.nav, vib)

    var alertaVista by remember { mutableLongStateOf(0L) }
    LaunchedEffect(e.alerta?.id) {
        val a = e.alerta ?: return@LaunchedEffect
        if (a.id != alertaVista) {
            if (a.tipo == TipoAlerta.INFO) vib.toque() else vib.alerta()
        }
    }
    LaunchedEffect(e.nav != null) {
        if (e.nav != null && nav.currentDestination?.route != "nav" && nav.currentDestination?.route != "mapa") {
            nav.navigate("nav")
        }
    }

    MaterialTheme {
        AppScaffold(modifier = Modifier.background(FondoPizarra)) {
            val a = e.alerta
            if (a != null && a.id != alertaVista && (a.pideRespuesta || a.tipo == TipoAlerta.RIESGO)) {
                PantallaAlerta(
                    texto = a.texto,
                    pide = a.pideRespuesta,
                    tipo = a.tipo,
                    ok = {
                        alertaVista = a.id
                        if (a.pideRespuesta) enviar(Comando(TipoComando.ESTOY_BIEN))
                    },
                )
                return@AppScaffold
            }
            SwipeDismissableNavHost(navController = nav, startDestination = "inicio") {
                val actual = { estadoState.value ?: EstadoReloj() }
                composable("inicio") {
                    PantallaInicio(actual(), conectadoState.value, enviar, nav::navigate)
                }
                composable("hoteles") {
                    PantallaHoteles(actual(), enviar) { nav.navigate("rutas") }
                }
                composable("rutas") {
                    PantallaRutas(
                        e = actual(),
                        enviar = enviar,
                        verMapa = { nav.navigate("mapa") },
                        irHoteles = { nav.navigate("hoteles") },
                    )
                }
                composable("nav") {
                    PantallaNav(
                        e = actual(),
                        enviar = enviar,
                        conectado = conectadoState.value,
                        rumboLocal = rumboLocal,
                        girarBrujula = girarBrujula,
                        irRutas = { nav.navigate("rutas") },
                        irHoteles = { nav.navigate("hoteles") },
                    )
                }
                composable("mapa") {
                    PantallaMapaReloj(
                        e = actual(),
                        rumboLocal = rumboLocal,
                        girarBrujula = girarBrujula,
                    )
                }
                composable("ayuda") { PantallaAyuda() }
            }
        }
    }
}

/** Vibra al cambiar de giro y otra vez al acercarse (< 30 m). */
@Composable
fun VibracionesNavegacion(n: NavActual?, vib: Vibracion) {
    var clave by remember { mutableStateOf("") }
    var avisoCerca by remember { mutableStateOf("") }
    LaunchedEffect(n) {
        if (n == null) { clave = ""; return@LaunchedEffect }
        val k = "${n.giro}|${n.calle}|${n.indicacion}"
        if (k != clave) {
            clave = k
            vib.giro(n.giro)
        } else if (n.metrosAlGiro < 30 && avisoCerca != k && n.giro != Giro.RECTO) {
            avisoCerca = k
            vib.giro(n.giro)
        }
    }
}

@Composable
fun PantallaInicio(e: EstadoReloj, conectado: Boolean, enviar: (Comando) -> Unit, ir: (String) -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Sereno", color = Salvia, fontWeight = FontWeight.Bold) } }
            item {
                Text(
                    (if (conectado) "● " else "○ ") + e.ciudad.ifBlank { "Ubicación GPS" } + if (!conectado) " · sin enlace" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conectado) Salvia else ArenaSuave,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (e.buscando) item { Text("Actualizando…", color = TextoTenue, style = MaterialTheme.typography.bodySmall) }
            e.nav?.let { n ->
                item {
                    Boton(
                        "🧭 En ruta · ${Geo.textoDistancia(n.metrosAlGiro)}",
                        "${textoGiro(n.giro)} en ${n.calle}",
                        Salvia.copy(alpha = 0.28f),
                    ) { ir("nav") }
                }
            }
            item {
                Boton(
                    "🏨 Hoteles cerca",
                    if (e.hoteles.isEmpty()) "Buscar desde mi ubicación" else "${e.hoteles.size} opciones disponibles",
                ) {
                    if (e.hoteles.isEmpty()) enviar(Comando(TipoComando.BUSCAR_HOTELES, "hotel barato cerca"))
                    ir("hoteles")
                }
            }
            if (e.rutas.isNotEmpty()) {
                item { Boton("🚌 Rutas (${e.rutas.size})", e.mapa?.destinoNombre ?: "Opciones de llegada") { ir("rutas") } }
            }
            if (e.mapa != null || e.hoteles.isNotEmpty()) {
                item {
                    Boton(
                        "🗺 Croquis del área",
                        if (e.mapa?.destinoNombre?.isNotBlank() == true) e.mapa!!.destinoNombre else "Ubicación, hoteles y ruta",
                    ) { ir("mapa") }
                }
            }
            item { CompactButton(onClick = { ir("ayuda") }, label = { Text("Guía de vibración", color = TextoTenue) }) }
        }
    }
}

@Composable
fun PantallaHoteles(e: EstadoReloj, enviar: (Comando) -> Unit, verRutas: () -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Alojamiento", color = TextoClaro) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Orden.DISTANCIA to "Cerca", Orden.PRECIO to "$", Orden.VALORACION to "★").forEach { (o, t) ->
                        CompactButton(
                            onClick = { enviar(Comando(TipoComando.ORDENAR, o.name)) },
                            label = { Text(t, color = if (e.orden == o) Salvia else TextoClaro) },
                        )
                    }
                }
            }
            if (e.buscando) item { Text("Buscando cerca…", color = TextoTenue) }
            if (!e.buscando && e.hoteles.isEmpty()) {
                item {
                    Text(
                        "Sin resultados aún. Toca abajo para buscar cerca de ti.",
                        textAlign = TextAlign.Center,
                        color = TextoTenue,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            e.hoteles.forEach { h ->
                item(key = h.id) {
                    Boton(
                        h.nombre,
                        "$${h.precioEstimado} · ${Geo.textoDistancia(h.distanciaM)}" + if (h.estrellas > 0) " · ${h.estrellas}★" else "",
                        if (h.id == e.hotelElegido) Salvia.copy(alpha = 0.28f) else null,
                    ) {
                        enviar(Comando(TipoComando.ELEGIR_HOTEL, h.id))
                        verRutas()
                    }
                }
            }
            item {
                CompactButton(
                    onClick = { enviar(Comando(TipoComando.BUSCAR_HOTELES, "hotel cerca")) },
                    label = { Text("🔄 Actualizar cercanos", color = AzulSereno) },
                )
            }
        }
    }
}

@Composable
fun PantallaRutas(
    e: EstadoReloj,
    enviar: (Comando) -> Unit,
    verMapa: () -> Unit,
    irHoteles: () -> Unit,
) {
    val lista = rememberTransformingLazyColumnState()
    val hotel = e.hoteles.firstOrNull { it.id == e.hotelElegido }
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item {
                ListHeader {
                    Text(
                        hotel?.nombre ?: "Rutas",
                        maxLines = 2,
                        color = TextoClaro,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (e.buscando) item { Text("Calculando opciones…", color = TextoTenue) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (e.mapa != null) {
                        CompactButton(onClick = verMapa, label = { Text("🗺 Croquis", color = Salvia) })
                    }
                    CompactButton(onClick = irHoteles, label = { Text("🏨 Hoteles", color = AzulSereno) })
                }
            }
            e.rutas.forEachIndexed { i, r ->
                item(key = "r$i") {
                    val resumen = r.tramos.joinToString(" ") { t ->
                        when (t.modo) {
                            Modo.CAMINAR -> "🚶${t.minutos}m"
                            Modo.TAXI -> "🚕"
                            Modo.METRO -> "🚇${t.linea.substringAfter(' ')}"
                            else -> "🚌${t.linea.substringAfter(' ', "")}"
                        }
                    }
                    val total = r.costo + (hotel?.precioEstimado ?: 0)
                    Boton(
                        "${r.minutos} min · $${r.costo.roundToInt()}",
                        "$resumen\nTotal+noche ≈ $${total.roundToInt()}",
                    ) {
                        enviar(Comando(TipoComando.ELEGIR_RUTA, i.toString()))
                        enviar(Comando(TipoComando.INICIAR_NAV, i.toString()))
                    }
                }
            }
        }
    }
}

@Composable
fun PantallaNav(
    e: EstadoReloj,
    enviar: (Comando) -> Unit,
    conectado: Boolean,
    rumboLocal: Float?,
    girarBrujula: (Float) -> Unit,
    irRutas: () -> Unit,
    irHoteles: () -> Unit,
) {
    val n = e.nav
    val lista = rememberTransformingLazyColumnState()
    var vistaCompleta by remember { mutableStateOf(false) }

    if (e.soloVibracion && n != null) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { enviar(Comando(TipoComando.SOLO_VIBRACION)) })
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(Geo.textoDistancia(n.metrosAlGiro), color = TextoTenue, fontSize = 15.sp)
        }
        return
    }

    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            if (n == null) {
                val info = e.alerta?.takeIf { it.tipo == TipoAlerta.INFO }?.texto
                item { ListHeader { Text(if (info != null) "✓ Llegaste" else "Sin ruta activa", color = Salvia) } }
                item {
                    Text(
                        info ?: "Elige un hotel y una ruta para ver el croquis y la guía paso a paso.",
                        textAlign = TextAlign.Center,
                        color = TextoTenue,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactButton(onClick = irHoteles, label = { Text("🏨 Hoteles", color = Salvia) })
                        if (e.rutas.isNotEmpty()) {
                            CompactButton(onClick = irRutas, label = { Text("🚌 Rutas", color = AzulSereno) })
                        }
                    }
                }
                return@TransformingLazyColumn
            }

            // Si el reloj tiene lectura de brújula local (sensor del emulador o giro interactivo), orienta la flecha con ella
            val gradosFlecha = when {
                n.giro == Giro.LLEGADA -> 0
                rumboLocal != null -> Geo.diferenciaAngulo(n.rumboObjetivo.toDouble(), rumboLocal.toDouble())
                else -> n.flechaGrados
            }

            // 1) Encabezado compacto: Flecha + Distancia + Calle
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Flecha(gradosFlecha, Modifier.size(38.dp))
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            Geo.textoDistancia(n.metrosAlGiro),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextoClaro,
                        )
                        Text(
                            "${textoGiro(n.giro)} · ${n.calle}",
                            fontSize = 11.sp,
                            color = Salvia,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 2) Croquis interactivo en vivo de la ruta + tu posición + flecha hacia dónde dirigirte
            item {
                CroquisRutaInteractivo(
                    e = e,
                    gradosFlecha = gradosFlecha,
                    rumboLocal = rumboLocal,
                    vistaCompleta = vistaCompleta,
                    alCambiarVista = { vistaCompleta = !vistaCompleta },
                    alGirarBrujula = girarBrujula,
                )
            }

            n.indicacion?.let {
                item {
                    Text(
                        it,
                        color = ArenaSuave,
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 3) Controles rápidos sin salir de la navegación (Simular, Brújula laptop, Rutas, Detener)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactButton(
                        onClick = { girarBrujula(-30f) },
                        label = { Text("↺", color = TextoClaro) },
                    )
                    CompactButton(
                        onClick = { enviar(Comando(TipoComando.SIMULAR_NAV)) },
                        label = {
                            Text(
                                if (n.simulando) "⏸ Pausa" else "▶ Avance",
                                color = if (n.simulando) ArenaSuave else Salvia,
                            )
                        },
                    )
                    CompactButton(
                        onClick = { girarBrujula(30f) },
                        label = { Text("↻", color = TextoClaro) },
                    )
                }
            }

            item {
                Text(
                    "Faltan ${Geo.textoDistancia(n.metrosRestantes)} (~${n.minutosRestantes} min)" +
                        (rumboLocal?.let { " · 🧭 ${it.roundToInt()}°" } ?: "") +
                        if (!conectado) "\n(sin enlace)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextoTenue,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactButton(onClick = irRutas, label = { Text("⇄ Ruta", color = AzulSereno) })
                    CompactButton(onClick = irHoteles, label = { Text("🏨 Hotel", color = TextoClaro) })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactButton(
                        onClick = { enviar(Comando(TipoComando.SOLO_VIBRACION)) },
                        label = { Text("Modo discreto", color = TextoTenue) },
                    )
                    CompactButton(
                        onClick = { enviar(Comando(TipoComando.DETENER_NAV)) },
                        label = { Text("Detener", color = ArenaSuave) },
                    )
                }
            }
        }
    }
}

/**
 * Pequeño croquis de la ruta integrado en la pantalla del reloj:
 * - En modo "Cerca" centra el croquis en tu posición actual (`yo`) y muestra claramente por qué tramo
 *   vas, dónde está el siguiente giro y una flecha indicando hacia dónde dirigirte.
 * - En modo "Total" encuadra toda la ruta de principio a fin.
 * - Permite arrastrar horizontalmente o usar ↺/↻ para simular la brújula del reloj en la laptop.
 */
@Composable
fun CroquisRutaInteractivo(
    e: EstadoReloj,
    gradosFlecha: Int,
    rumboLocal: Float?,
    vistaCompleta: Boolean,
    alCambiarVista: () -> Unit,
    alGirarBrujula: (Float) -> Unit,
) {
    val m = e.mapa
    val n = e.nav
    val puntos = remember(m, e.hoteles) {
        buildList {
            m?.yo?.let { add(it) }
            n?.puntoGiro?.let { add(it) }
            m?.destino?.let { add(it) }
            m?.trazos?.forEach { addAll(it.puntos) }
            if (isEmpty()) addAll(e.hoteles.map { it.punto })
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SuperficiePizarra)
            .pointerInput(vistaCompleta) {
                detectTapGestures(
                    onTap = { alCambiarVista() },
                    onDoubleTap = { alGirarBrujula(0f) },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    alGirarBrujula(dragAmount * 0.45f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (puntos.isEmpty()) {
            Text("Sin croquis disponible", fontSize = 10.sp, color = TextoTenue)
            return@Box
        }

        val yo = m?.yo ?: puntos.first()
        val pGiro = n?.puntoGiro

        // En modo cercano, encuadramos alrededor de `yo` y del próximo giro; en modo completo, toda la ruta
        val puntosEncuadre = if (!vistaCompleta && m?.yo != null) {
            buildList {
                add(yo)
                if (pGiro != null) add(pGiro)
                m.trazos.flatMap { it.puntos }.filter { Geo.distancia(yo, it) <= 450.0 }.forEach { add(it) }
            }
        } else {
            puntos
        }

        val minLat = puntosEncuadre.minOf { it.lat }
        val maxLat = puntosEncuadre.maxOf { it.lat }
        val minLon = puntosEncuadre.minOf { it.lon }
        val maxLon = puntosEncuadre.maxOf { it.lon }
        val cLat = if (!vistaCompleta && m?.yo != null) yo.lat else (minLat + maxLat) / 2.0
        val cLon = if (!vistaCompleta && m?.yo != null) yo.lon else (minLon + maxLon) / 2.0
        val cosLat = cos(Math.toRadians(cLat)).coerceAtLeast(0.5)
        val spanMin = if (vistaCompleta) 0.005 else 0.0028
        val spanLon = ((maxLon - minLon) * cosLat).coerceAtLeast(spanMin)
        val spanLat = (maxLat - minLat).coerceAtLeast(spanMin)
        val span = maxOf(spanLon, spanLat) * (if (vistaCompleta) 1.55 else 2.1)
        val rotacionMapa = -(rumboLocal ?: 0f)

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val escala = minOf(w, h) / span.toFloat()

            fun aXY(p: Punto): Offset {
                val x = w / 2f + ((p.lon - cLon) * cosLat).toFloat() * escala
                val y = h / 2f - (p.lat - cLat).toFloat() * escala
                return Offset(x, y)
            }

            // Anillo de referencia circular suave
            val radioRef = minOf(w, h) * 0.42f
            drawCircle(
                color = Color(0xFF2E3C4A),
                radius = radioRef,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = 1.5f),
            )

            // Rotamos el croquis según la brújula del reloj para que "de frente" coincida con tu mirada
            rotate(degrees = rotacionMapa, pivot = Offset(w / 2f, h / 2f)) {
                // 1) Trazos de la ruta (caminar en arena punteado, transporte en azul sereno)
                m?.trazos?.forEach { tr ->
                    if (tr.puntos.size >= 2) {
                        val path = Path().apply {
                            val p0 = aXY(tr.puntos.first())
                            moveTo(p0.x, p0.y)
                            tr.puntos.drop(1).forEach { pt ->
                                val o = aXY(pt)
                                lineTo(o.x, o.y)
                            }
                        }
                        val colorTramo = when (tr.modo) {
                            Modo.CAMINAR -> ArenaSuave
                            Modo.TAXI -> Color(0xFFB392F0)
                            else -> AzulSereno
                        }
                        val efecto = if (tr.modo == Modo.CAMINAR) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null
                        drawPath(
                            path,
                            Color(0xAA0D1318),
                            style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        drawPath(
                            path,
                            colorTramo,
                            style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = efecto),
                        )
                    }
                }

                // 2) Línea guía directa hacia el próximo giro + marcador del giro
                if (m?.yo != null && pGiro != null) {
                    val oYo = aXY(m.yo!!)
                    val oGiro = aXY(pGiro)
                    drawLine(
                        color = Salvia.copy(alpha = 0.55f),
                        start = oYo,
                        end = oGiro,
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                    )
                    drawCircle(Color(0xFF161D24), radius = 6.5f, center = oGiro)
                    drawCircle(ArenaSuave, radius = 4.5f, center = oGiro)
                }

                // 3) Destino final (verde salvia con borde claro)
                m?.destino?.let { dest ->
                    val o = aXY(dest)
                    drawCircle(TextoClaro, radius = 7.5f, center = o)
                    drawCircle(Salvia, radius = 5.5f, center = o)
                }

                // 4) Tu posición actual (punto azul + halo)
                m?.yo?.let { posYo ->
                    val o = aXY(posYo)
                    drawCircle(AzulSereno.copy(alpha = 0.28f), radius = 13f, center = o)
                    drawCircle(TextoClaro, radius = 6.5f, center = o)
                    drawCircle(AzulSereno, radius = 4.5f, center = o)
                }
            }

            // 5) Flecha direccional sobre tu posición indicando exactamente hacia dónde dirigirte
            m?.yo?.let { posYo ->
                val centroYo = if (!vistaCompleta) Offset(w / 2f, h / 2f) else {
                    val sinRot = aXY(posYo)
                    val rad = Math.toRadians(rotacionMapa.toDouble())
                    val dx = sinRot.x - w / 2f
                    val dy = sinRot.y - h / 2f
                    Offset(
                        w / 2f + (dx * cos(rad) - dy * sin(rad)).toFloat(),
                        h / 2f + (dx * sin(rad) + dy * cos(rad)).toFloat(),
                    )
                }
                rotate(degrees = gradosFlecha.toFloat(), pivot = centroYo) {
                    val punta = Offset(centroYo.x, centroYo.y - 20f)
                    val izq = Offset(centroYo.x - 6f, centroYo.y - 9f)
                    val der = Offset(centroYo.x + 6f, centroYo.y - 9f)
                    val flechaPath = Path().apply {
                        moveTo(punta.x, punta.y)
                        lineTo(der.x, der.y)
                        lineTo(izq.x, izq.y)
                        close()
                    }
                    drawPath(flechaPath, Salvia)
                }
            }
        }

        // Etiquetas sutiles en las esquinas del croquis
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (vistaCompleta) "Vista: Ruta total" else "Vista: Tu tramo (toca)",
                fontSize = 9.sp,
                color = TextoTenue,
            )
            Text(
                "🔵Tú 🟡Giro 🟢Meta",
                fontSize = 8.sp,
                color = TextoTenue,
            )
        }
    }
}

/** Pantalla dedicada del croquis a pantalla completa en el reloj. */
@Composable
fun PantallaMapaReloj(
    e: EstadoReloj,
    rumboLocal: Float?,
    girarBrujula: (Float) -> Unit,
) {
    var vistaCompleta by remember { mutableStateOf(true) }
    val gradosFlecha = e.nav?.let { n ->
        if (rumboLocal != null) Geo.diferenciaAngulo(n.rumboObjetivo.toDouble(), rumboLocal.toDouble())
        else n.flechaGrados
    } ?: 0

    Column(
        Modifier.fillMaxSize().background(FondoPizarra).padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            e.mapa?.destinoNombre?.ifBlank { e.ciudad.ifBlank { "Croquis" } } ?: "Croquis",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Salvia,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CroquisRutaInteractivo(
                e = e,
                gradosFlecha = gradosFlecha,
                rumboLocal = rumboLocal,
                vistaCompleta = vistaCompleta,
                alCambiarVista = { vistaCompleta = !vistaCompleta },
                alGirarBrujula = girarBrujula,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactButton(onClick = { girarBrujula(-30f) }, label = { Text("↺", color = TextoClaro) })
            CompactButton(
                onClick = { vistaCompleta = !vistaCompleta },
                label = { Text(if (vistaCompleta) "Acercar" else "Alejar", color = Salvia) },
            )
            CompactButton(onClick = { girarBrujula(30f) }, label = { Text("↻", color = TextoClaro) })
        }
    }
}

@Composable
fun PantallaAlerta(texto: String, pide: Boolean, tipo: TipoAlerta, ok: () -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item {
                ListHeader {
                    Text(
                        if (tipo == TipoAlerta.RIESGO) "Aviso de zona" else "Sereno",
                        color = if (tipo == TipoAlerta.RIESGO) ArenaSuave else Salvia,
                    )
                }
            }
            item { Text(texto, textAlign = TextAlign.Center, color = TextoClaro) }
            item { Boton(if (pide) "Estoy bien, continuar" else "Entendido", null, Salvia.copy(alpha = 0.35f)) { ok() } }
        }
    }
}

@Composable
fun PantallaAyuda() {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Guía háptica", color = Salvia) } }
            listOf(
                "Izquierda: 2 toques cortos",
                "Derecha: 3 toques cortos",
                "Sigue recto: 1 toque suave",
                "Vuelta en U: 1 largo + 2 cortos",
                "Llegada: 1 vibración larga",
                "Croquis: toca para acercar/alejar",
                "Brújula: usa ↺ / ↻ o arrastra",
                "Modo discreto: doble toque para salir",
            ).forEach {
                item { Text(it, color = TextoClaro, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ---------- Componentes ----------

@Composable
fun Boton(titulo: String, sub: String?, fondo: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (fondo != null) {
            ButtonDefaults.buttonColors(
                containerColor = fondo,
                contentColor = TextoClaro,
                secondaryContentColor = TextoTenue,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = SuperficiePizarra,
                contentColor = TextoClaro,
                secondaryContentColor = TextoTenue,
            )
        },
        label = { Text(titulo, maxLines = 2) },
        secondaryLabel = sub?.let { { Text(it, maxLines = 3) } },
    )
}

@Composable
fun Flecha(grados: Int, modifier: Modifier) {
    Canvas(modifier.rotate(grados.toFloat())) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w * 0.88f, h * 0.85f)
            lineTo(w / 2, h * 0.66f)
            lineTo(w * 0.12f, h * 0.85f)
            close()
        }
        drawPath(p, Salvia)
        drawCircle(TextoClaro, radius = w * 0.07f, center = Offset(w / 2, h * 0.66f))
    }
}

fun textoGiro(g: Giro) = when (g) {
    Giro.SALIDA -> "Sal"
    Giro.RECTO -> "Sigue recto"
    Giro.LIGERA_IZQ -> "Ligera izq."
    Giro.IZQ -> "Gira izq."
    Giro.FUERTE_IZQ -> "Fuerte izq."
    Giro.LIGERA_DER -> "Ligera der."
    Giro.DER -> "Gira der."
    Giro.FUERTE_DER -> "Fuerte der."
    Giro.VUELTA_U -> "Vuelta en U"
    Giro.ROTONDA -> "Glorieta"
    Giro.LLEGADA -> "Llegas"
}
