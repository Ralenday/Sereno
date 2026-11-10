package com.ralenday.sereno.wear

import android.os.Bundle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
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
import com.ralenday.sereno.core.conRumbo
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Niveles de tamaño de letra del reloj: multiplicador y nombre. */
val ESCALAS_LETRA = listOf(1.0f to "Normal", 1.15f to "Grande", 1.3f to "Muy grande")

// Paleta suave pero con alto contraste en botones para lectura clara en el reloj
val FondoPizarra = Color(0xFF12181F)
val SuperficieBoton = Color(0xFF243240)
val SuperficieActiva = Color(0xFF1F4B3F)
val BordeBoton = Color(0xFF4B657C)
val BordeActivo = Color(0xFF68B69F)
val Salvia = Color(0xFF6EE7B7)
val AzulSereno = Color(0xFF7DD3FC)
val ArenaSuave = Color(0xFFFCD34D)
val TextoClaro = Color(0xFFF8FAFC)
val TextoSubtitulo = Color(0xFFCBD5E1)
val TextoTenue = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppReloj() }
    }
}

/**
 * Calcula los grados de la flecha en el reloj usando en tiempo real la brújula del celular
 * (`e.mapa?.rumbo` y `e.nav?.flechaGrados`) más cualquier ajuste manual (`↺` / `↻`).
 */
fun calcularGradosFlecha(e: EstadoReloj, ajusteManual: Float): Int {
    val n = e.nav
    val rumboCelular = (e.mapa?.rumbo ?: 0).toFloat()
    val rumboEfectivo = (((rumboCelular + ajusteManual) % 360f) + 360f) % 360f
    if (n != null) {
        if (n.giro == Giro.LLEGADA) return 0
        return if (ajusteManual != 0f) {
            Geo.diferenciaAngulo(n.rumboObjetivo.toDouble(), rumboEfectivo.toDouble())
        } else {
            n.flechaGrados
        }
    }
    val yo = e.mapa?.yo
    val dest = e.mapa?.destino
    if (yo != null && dest != null) {
        val rumboMeta = Geo.rumbo(yo, dest)
        return Geo.diferenciaAngulo(rumboMeta, rumboEfectivo.toDouble())
    }
    return rumboEfectivo.roundToInt()
}

fun calcularRumboEfectivo(e: EstadoReloj, ajusteManual: Float): Float {
    val rumboCelular = (e.mapa?.rumbo ?: 0).toFloat()
    return (((rumboCelular + ajusteManual) % 360f) + 360f) % 360f
}

@Composable
fun AppReloj() {
    val context = LocalContext.current
    val enlace = remember { Enlace.get(context) }
    val vib = remember { Vibracion(context) }
    val estadoState = enlace.estado.collectAsState()
    val conectadoState = enlace.conectado.collectAsState()
    val rumboVivoState = enlace.rumboVivo.collectAsState()
    // La brújula en vivo (canal ligero) se aplica encima del último estado completo recibido
    val estado = estadoState.value?.conRumbo(rumboVivoState.value)
    val scope = rememberCoroutineScope()
    val nav = rememberSwipeDismissableNavController()
    val e = estado ?: EstadoReloj()
    var ajusteManual by remember { mutableFloatStateOf(0f) }
    val prefs = remember { context.getSharedPreferences("sereno_reloj", android.content.Context.MODE_PRIVATE) }
    var nivelLetra by remember { mutableIntStateOf(prefs.getInt("letra", 0).coerceIn(0, ESCALAS_LETRA.lastIndex)) }
    val cambiarLetra: () -> Unit = {
        nivelLetra = (nivelLetra + 1) % ESCALAS_LETRA.size
        prefs.edit().putInt("letra", nivelLetra).apply()
    }
    val densidad = LocalDensity.current
    val girarBrujula: (Float) -> Unit = { delta ->
        ajusteManual = if (delta == 0f) 0f else ((ajusteManual + delta) % 360f + 360f) % 360f
    }
    val enviar: (Comando) -> Unit = { c -> vib.toque(); scope.launch { enlace.enviar(c) } }

    VibracionesNavegacion(e.nav, vib)

    var alertaVista by remember { mutableLongStateOf(0L) }
    LaunchedEffect(e.alerta?.id) {
        val a = e.alerta ?: return@LaunchedEffect
        if (a.id != alertaVista) {
            when (a.tipo) {
                TipoAlerta.INFO -> vib.toque()
                TipoAlerta.BAJADA -> vib.bajada()
                TipoAlerta.RIESGO -> vib.alerta()
                else -> vib.aviso()
            }
        }
    }
    LaunchedEffect(e.nav != null) {
        if (e.nav != null && nav.currentDestination?.route != "nav" && nav.currentDestination?.route != "mapa") {
            nav.navigate("nav")
        }
    }

    MaterialTheme {
      CompositionLocalProvider(
        LocalDensity provides Density(densidad.density, densidad.fontScale * ESCALAS_LETRA[nivelLetra].first),
      ) {
        AppScaffold(modifier = Modifier.background(FondoPizarra)) {
            val a = e.alerta
            if (a != null && a.id != alertaVista && (a.pideRespuesta || a.tipo == TipoAlerta.RIESGO || a.tipo == TipoAlerta.BAJADA)) {
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
                val actual = { estadoState.value?.conRumbo(rumboVivoState.value) ?: EstadoReloj() }
                composable("inicio") {
                    PantallaInicio(
                        actual(), conectadoState.value, ajusteManual, enviar, nav::navigate,
                        textoLetra = ESCALAS_LETRA[nivelLetra].second, cambiarLetra = cambiarLetra,
                    )
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
                        ajusteManual = ajusteManual,
                        girarBrujula = girarBrujula,
                        irRutas = { nav.navigate("rutas") },
                        irHoteles = { nav.navigate("hoteles") },
                    )
                }
                composable("mapa") {
                    PantallaMapaReloj(
                        e = actual(),
                        ajusteManual = ajusteManual,
                        girarBrujula = girarBrujula,
                    )
                }
                composable("ayuda") { PantallaAyuda() }
            }
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
fun PantallaInicio(
    e: EstadoReloj,
    conectado: Boolean,
    ajusteManual: Float,
    enviar: (Comando) -> Unit,
    ir: (String) -> Unit,
    textoLetra: String = "Normal",
    cambiarLetra: () -> Unit = {},
) {
    val lista = rememberTransformingLazyColumnState()
    val gradosFlecha = calcularGradosFlecha(e, ajusteManual)
    val rumboEfectivo = calcularRumboEfectivo(e, ajusteManual).roundToInt()

    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Flecha(gradosFlecha, Modifier.size(22.dp))
                    Text("Sereno", color = Salvia, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("🧭 ${rumboEfectivo}°", color = TextoSubtitulo, fontSize = 11.sp)
                }
            }
            item {
                Text(
                    (if (conectado) "● " else "○ ") + e.ciudad.ifBlank { "Ubicación GPS" } + if (!conectado) " · sin enlace" else "",
                    fontSize = 11.sp,
                    color = if (conectado) Salvia else ArenaSuave,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (e.buscando) {
                item { Text("Actualizando…", color = TextoSubtitulo, fontSize = 12.sp) }
            }
            e.nav?.let { n ->
                item {
                    Boton(
                        titulo = "🧭 En ruta · ${Geo.textoDistancia(n.metrosAlGiro)}",
                        sub = "${textoGiro(n.giro)} en ${n.calle}",
                        destacado = true,
                    ) { ir("nav") }
                }
            }
            item {
                Boton(
                    titulo = "🏨 Hoteles cerca",
                    sub = if (e.hoteles.isEmpty()) "Buscar desde mi ubicación" else "${e.hoteles.size} opciones disponibles",
                ) {
                    if (e.hoteles.isEmpty()) enviar(Comando(TipoComando.BUSCAR_HOTELES, "hotel barato cerca"))
                    ir("hoteles")
                }
            }
            if (e.rutas.isNotEmpty()) {
                item {
                    Boton(
                        titulo = "🚌 Rutas (${e.rutas.size})",
                        sub = e.mapa?.destinoNombre?.ifBlank { "Opciones de llegada" } ?: "Opciones de llegada",
                    ) { ir("rutas") }
                }
            }
            if (e.mapa != null || e.hoteles.isNotEmpty()) {
                item {
                    Boton(
                        titulo = "🗺 Croquis y brújula",
                        sub = if (e.mapa?.destinoNombre?.isNotBlank() == true) e.mapa!!.destinoNombre else "Ver mapa y flecha en vivo",
                    ) { ir("mapa") }
                }
            }
            item {
                BotonPastilla(
                    texto = "Aa Letra: $textoLetra",
                    colorTexto = AzulSereno,
                    onClick = cambiarLetra,
                )
            }
            item {
                BotonPastilla(
                    texto = "Guía de vibración",
                    colorTexto = TextoClaro,
                    onClick = { ir("ayuda") },
                )
            }
        }
    }
}

@Composable
fun PantallaHoteles(e: EstadoReloj, enviar: (Comando) -> Unit, verRutas: () -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Alojamiento", color = TextoClaro, fontWeight = FontWeight.Bold) } }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(Orden.DISTANCIA to "Cerca", Orden.PRECIO to "$ Precio", Orden.VALORACION to "★ Top").forEach { (o, t) ->
                        val activo = e.orden == o
                        BotonPastilla(
                            texto = t,
                            activo = activo,
                            colorTexto = if (activo) Salvia else TextoClaro,
                            onClick = { enviar(Comando(TipoComando.ORDENAR, o.name)) },
                        )
                    }
                }
            }
            if (e.buscando) item { Text("Buscando cerca…", color = TextoSubtitulo, fontSize = 12.sp) }
            if (!e.buscando && e.hoteles.isEmpty()) {
                item {
                    Text(
                        "Sin resultados aún. Toca abajo para buscar cerca de ti.",
                        textAlign = TextAlign.Center,
                        color = TextoSubtitulo,
                        fontSize = 12.sp,
                    )
                }
            }
            e.hoteles.forEach { h ->
                item(key = h.id) {
                    Boton(
                        titulo = h.nombre,
                        sub = "$${h.precioEstimado} · ${Geo.textoDistancia(h.distanciaM)}" +
                            (if (h.estrellas > 0) " · ${h.estrellas}★" else "") +
                            (if (h.calificacion > 0) " · ${"%.1f".format(h.calificacion)}" else ""),
                        destacado = h.id == e.hotelElegido,
                    ) {
                        enviar(Comando(TipoComando.ELEGIR_HOTEL, h.id))
                        verRutas()
                    }
                }
            }
            item {
                BotonPastilla(
                    texto = "🔄 Actualizar cercanos",
                    colorTexto = AzulSereno,
                    onClick = { enviar(Comando(TipoComando.BUSCAR_HOTELES, "hotel cerca")) },
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
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (e.buscando) item { Text("Calculando opciones…", color = TextoSubtitulo, fontSize = 12.sp) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (e.mapa != null) {
                        BotonPastilla(
                            texto = "🗺 Croquis",
                            activo = true,
                            colorTexto = Salvia,
                            onClick = verMapa,
                        )
                    }
                    BotonPastilla(
                        texto = "🏨 Hoteles",
                        colorTexto = AzulSereno,
                        onClick = irHoteles,
                    )
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
                        titulo = "${r.minutos} min · $${r.costo.roundToInt()}",
                        sub = "$resumen\nTotal+noche ≈ $${total.roundToInt()}",
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
    ajusteManual: Float,
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
            Text(Geo.textoDistancia(n.metrosAlGiro), color = TextoClaro, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        return
    }

    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            if (n == null) {
                val info = e.alerta?.takeIf { it.tipo == TipoAlerta.INFO }?.texto
                item { ListHeader { Text(if (info != null) "✓ Llegaste" else "Sin ruta activa", color = Salvia, fontWeight = FontWeight.Bold) } }
                item {
                    Text(
                        info ?: "Elige un hotel y una ruta para ver el croquis y la guía paso a paso.",
                        textAlign = TextAlign.Center,
                        color = TextoSubtitulo,
                        fontSize = 12.sp,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotonPastilla(texto = "🏨 Hoteles", activo = true, colorTexto = Salvia, onClick = irHoteles)
                        if (e.rutas.isNotEmpty()) {
                            BotonPastilla(texto = "🚌 Rutas", colorTexto = AzulSereno, onClick = irRutas)
                        }
                    }
                }
                return@TransformingLazyColumn
            }

            // Orientamos la flecha con la brújula en vivo enviada desde el celular (+ ajuste manual opcional)
            val gradosFlecha = calcularGradosFlecha(e, ajusteManual)
            val rumboEfectivo = calcularRumboEfectivo(e, ajusteManual)

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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
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
                    rumboMapa = rumboEfectivo,
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
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 3) Controles rápidos claros y bien legibles (Simular, ajuste brújula, Rutas, Detener)
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotonPastilla(
                        texto = "↺",
                        colorTexto = TextoClaro,
                        onClick = { girarBrujula(-30f) },
                    )
                    BotonPastilla(
                        texto = if (n.simulando) "⏸ Pausa" else "▶ Simular",
                        activo = !n.simulando,
                        colorTexto = if (n.simulando) ArenaSuave else Salvia,
                        onClick = { enviar(Comando(TipoComando.SIMULAR_NAV)) },
                    )
                    BotonPastilla(
                        texto = "↻",
                        colorTexto = TextoClaro,
                        onClick = { girarBrujula(30f) },
                    )
                }
            }

            item {
                Text(
                    "Faltan ${Geo.textoDistancia(n.metrosRestantes)} (~${n.minutosRestantes} min) · 🧭 ${rumboEfectivo.roundToInt()}°" +
                        if (!conectado) "\n(sin enlace)" else "",
                    fontSize = 11.sp,
                    color = TextoSubtitulo,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotonPastilla(
                        texto = "⇄ Ruta",
                        colorTexto = AzulSereno,
                        onClick = irRutas,
                    )
                    BotonPastilla(
                        texto = "🏨 Hotel",
                        colorTexto = TextoClaro,
                        onClick = irHoteles,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotonPastilla(
                        texto = "Vibración",
                        colorTexto = TextoSubtitulo,
                        onClick = { enviar(Comando(TipoComando.SOLO_VIBRACION)) },
                    )
                    BotonPastilla(
                        texto = "Detener",
                        colorTexto = ArenaSuave,
                        onClick = { enviar(Comando(TipoComando.DETENER_NAV)) },
                    )
                }
            }
        }
    }
}

/**
 * Pequeño croquis de la ruta integrado en la pantalla del reloj:
 * - Usa la brújula del celular en tiempo real (`rumboMapa`) para orientar el croquis y la flecha.
 * - En modo "Cerca" centra el croquis en tu posición actual (`yo`) y muestra claramente por qué tramo
 *   vas, dónde está el siguiente giro y una flecha indicando hacia dónde dirigirte.
 * - En modo "Total" encuadra toda la ruta de principio a fin.
 */
@Composable
fun CroquisRutaInteractivo(
    e: EstadoReloj,
    gradosFlecha: Int,
    rumboMapa: Float,
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
            .background(SuperficieBoton)
            .border(1.dp, BordeBoton, RoundedCornerShape(16.dp))
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
            Text("Sin croquis disponible", fontSize = 11.sp, color = TextoSubtitulo)
            return@Box
        }

        val yo = m?.yo ?: puntos.first()
        val pGiro = n?.puntoGiro

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
        val rotacionMapa = -anguloSuave(rumboMapa)

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val escala = minOf(w, h) / span.toFloat()

            fun aXY(p: Punto): Offset {
                val x = w / 2f + ((p.lon - cLon) * cosLat).toFloat() * escala
                val y = h / 2f - (p.lat - cLat).toFloat() * escala
                return Offset(x, y)
            }

            val radioRef = minOf(w, h) * 0.42f
            drawCircle(
                color = Color(0xFF334659),
                radius = radioRef,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = 1.5f),
            )

            rotate(degrees = rotacionMapa, pivot = Offset(w / 2f, h / 2f)) {
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
                            Modo.TAXI -> Color(0xFFC4B5FD)
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

                if (m?.yo != null && pGiro != null) {
                    val oYo = aXY(m.yo!!)
                    val oGiro = aXY(pGiro)
                    drawLine(
                        color = Salvia.copy(alpha = 0.65f),
                        start = oYo,
                        end = oGiro,
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                    )
                    drawCircle(Color(0xFF12181F), radius = 6.5f, center = oGiro)
                    drawCircle(ArenaSuave, radius = 4.5f, center = oGiro)
                }

                m?.destino?.let { dest ->
                    val o = aXY(dest)
                    drawCircle(TextoClaro, radius = 7.5f, center = o)
                    drawCircle(Salvia, radius = 5.5f, center = o)
                }

                m?.yo?.let { posYo ->
                    val o = aXY(posYo)
                    drawCircle(AzulSereno.copy(alpha = 0.28f), radius = 13f, center = o)
                    drawCircle(TextoClaro, radius = 6.5f, center = o)
                    drawCircle(AzulSereno, radius = 4.5f, center = o)
                }
            }

            // Flecha direccional sobre tu posición indicando exactamente hacia dónde dirigirte
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
                    val punta = Offset(centroYo.x, centroYo.y - 21f)
                    val izq = Offset(centroYo.x - 6.5f, centroYo.y - 9f)
                    val der = Offset(centroYo.x + 6.5f, centroYo.y - 9f)
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

        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (vistaCompleta) "Vista: Total" else "Vista: Cerca",
                fontSize = 9.sp,
                color = TextoSubtitulo,
            )
            Text(
                "🧭 ${rumboMapa.roundToInt()}°",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Salvia,
            )
        }
    }
}

/** Pantalla dedicada del croquis y brújula en el reloj. */
@Composable
fun PantallaMapaReloj(
    e: EstadoReloj,
    ajusteManual: Float,
    girarBrujula: (Float) -> Unit,
) {
    var vistaCompleta by remember { mutableStateOf(true) }
    val gradosFlecha = calcularGradosFlecha(e, ajusteManual)
    val rumboEfectivo = calcularRumboEfectivo(e, ajusteManual)

    Column(
        Modifier.fillMaxSize().background(FondoPizarra).padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Flecha(gradosFlecha, Modifier.size(18.dp))
            Text(
                e.mapa?.destinoNombre?.ifBlank { e.ciudad.ifBlank { "Croquis" } } ?: "Croquis",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Salvia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            CroquisRutaInteractivo(
                e = e,
                gradosFlecha = gradosFlecha,
                rumboMapa = rumboEfectivo,
                vistaCompleta = vistaCompleta,
                alCambiarVista = { vistaCompleta = !vistaCompleta },
                alGirarBrujula = girarBrujula,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotonPastilla(
                texto = "↺",
                colorTexto = TextoClaro,
                onClick = { girarBrujula(-30f) },
            )
            BotonPastilla(
                texto = if (vistaCompleta) "Acercar" else "Alejar",
                activo = true,
                colorTexto = Salvia,
                onClick = { vistaCompleta = !vistaCompleta },
            )
            BotonPastilla(
                texto = "↻",
                colorTexto = TextoClaro,
                onClick = { girarBrujula(30f) },
            )
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
                        when (tipo) {
                            TipoAlerta.RIESGO -> "⚠ Aviso de zona"
                            TipoAlerta.BAJADA -> "🚏 Tu parada"
                            else -> "Sereno"
                        },
                        color = if (tipo == TipoAlerta.RIESGO) ArenaSuave else Salvia,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item { Text(texto, textAlign = TextAlign.Center, color = TextoClaro, fontSize = 13.sp) }
            item {
                Boton(
                    titulo = if (pide) "Estoy bien, continuar" else "Entendido",
                    sub = null,
                    destacado = true,
                ) { ok() }
            }
        }
    }
}

@Composable
fun PantallaAyuda() {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Guía háptica", color = Salvia, fontWeight = FontWeight.Bold) } }
            listOf(
                "Izquierda: 2 toques cortos",
                "Derecha: 3 toques cortos",
                "Sigue recto: 1 toque suave",
                "Vuelta en U: 1 largo + 2 cortos",
                "Llegada: 1 vibración larga",
                "Tu parada: 2 cortas + 1 larga",
                "Zona de robos: 3 largas",
                "Brújula: usa el giro de tu celular",
                "Croquis: toca para acercar/alejar",
                "Modo discreto: doble toque para salir",
            ).forEach {
                item { Text(it, color = TextoClaro, fontSize = 12.sp) }
            }
        }
    }
}

// ---------- Botones de alto contraste y lectura clara en el reloj ----------

@Composable
fun Boton(
    titulo: String,
    sub: String?,
    destacado: Boolean = false,
    onClick: () -> Unit,
) {
    val fondo = if (destacado) SuperficieActiva else SuperficieBoton
    val borde = if (destacado) BordeActivo else BordeBoton
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(fondo)
            .border(1.dp, borde, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = titulo,
            color = TextoClaro,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!sub.isNullOrBlank()) {
            Text(
                text = sub,
                color = TextoSubtitulo,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun BotonPastilla(
    texto: String,
    activo: Boolean = false,
    colorTexto: Color = TextoClaro,
    onClick: () -> Unit,
) {
    val fondo = if (activo) SuperficieActiva else SuperficieBoton
    val borde = if (activo) BordeActivo else BordeBoton
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(fondo)
            .border(1.dp, borde, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = texto,
            color = colorTexto,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun Flecha(grados: Int, modifier: Modifier) {
    val suave = anguloSuave(grados.toFloat())
    Canvas(modifier.graphicsLayer { rotationZ = suave }) {
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
        drawCircle(TextoClaro, radius = w * 0.08f, center = Offset(w / 2, h * 0.66f))
    }
}

/**
 * Interpola un ángulo hacia su nuevo valor por el camino más corto (359° -> 1° gira 2°, no 358°),
 * para que la flecha y el croquis giren de forma continua entre lecturas de la brújula.
 */
@Composable
fun anguloSuave(objetivo: Float): Float {
    val anim = remember { Animatable(objetivo) }
    LaunchedEffect(objetivo) {
        val actual = anim.value
        val delta = ((((objetivo - actual) % 360f) + 540f) % 360f) - 180f
        anim.animateTo(actual + delta, tween(durationMillis = 140, easing = LinearEasing))
    }
    return anim.value
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
