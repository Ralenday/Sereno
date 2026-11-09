package com.ralenday.sereno.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

val Ambar = Color(0xFFFCA311)
val Rojo = Color(0xFFE5484D)
val Verde = Color(0xFF2E9E5B)
val AzulRuta = Color(0xFF00B8F0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppReloj() }
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
        AppScaffold {
            val a = e.alerta
            if (a != null && a.id != alertaVista && (a.pideRespuesta || a.tipo == TipoAlerta.RIESGO || a.tipo == TipoAlerta.PANICO_ENVIADO)) {
                PantallaAlerta(a.texto, a.pideRespuesta, a.tipo,
                    ok = { alertaVista = a.id; if (a.pideRespuesta) enviar(Comando(TipoComando.ESTOY_BIEN)) },
                    sos = { alertaVista = a.id; enviar(Comando(TipoComando.PANICO)) })
                return@AppScaffold
            }
            SwipeDismissableNavHost(navController = nav, startDestination = "inicio") {
                val actual = { estadoState.value ?: EstadoReloj() }
                composable("inicio") { PantallaInicio(actual(), conectadoState.value, enviar, nav::navigate) }
                composable("hoteles") { PantallaHoteles(actual(), enviar) { nav.navigate("rutas") } }
                composable("rutas") { PantallaRutas(actual(), enviar, verMapa = { nav.navigate("mapa") }) }
                composable("nav") { PantallaNav(actual(), enviar, conectadoState.value, verMapa = { nav.navigate("mapa") }) }
                composable("mapa") { PantallaMapaReloj(actual()) }
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
            item { ListHeader { Text("Sereno", color = Ambar, fontWeight = FontWeight.Bold) } }
            item {
                Text(
                    (if (conectado) "● " else "○ ") + e.ciudad.ifBlank { "Sin ciudad" } + if (!conectado) " · sin teléfono" else "",
                    style = MaterialTheme.typography.labelSmall, color = if (conectado) Verde else Rojo,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (e.buscando) item { Text("Buscando…", style = MaterialTheme.typography.bodySmall) }
            e.nav?.let { n ->
                item { Boton("Navegando", "${Geo.textoDistancia(n.metrosAlGiro)} · ${n.calle}", Ambar.copy(alpha = 0.35f)) { ir("nav") } }
            }
            item {
                Boton("Hoteles", if (e.hoteles.isEmpty()) "Buscar barato cerca" else "${e.hoteles.size} opciones") {
                    if (e.hoteles.isEmpty()) enviar(Comando(TipoComando.BUSCAR_HOTELES, "hotel barato cerca"))
                    ir("hoteles")
                }
            }
            if (e.rutas.isNotEmpty()) item { Boton("Cómo llegar", "${e.rutas.size} rutas") { ir("rutas") } }
            if (e.mapa != null || e.hoteles.isNotEmpty()) {
                item { Boton("Mapa", if (e.mapa?.destinoNombre?.isNotBlank() == true) e.mapa!!.destinoNombre else "Ver ruta y hoteles") { ir("mapa") } }
            }
            item { BotonSos { enviar(Comando(TipoComando.PANICO)) } }
            item { CompactButton(onClick = { ir("ayuda") }, label = { Text("Vibraciones") }) }
        }
    }
}

@Composable
fun PantallaHoteles(e: EstadoReloj, enviar: (Comando) -> Unit, verRutas: () -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Hoteles") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Orden.DISTANCIA to "Cerca", Orden.PRECIO to "$", Orden.VALORACION to "★").forEach { (o, t) ->
                        CompactButton(
                            onClick = { enviar(Comando(TipoComando.ORDENAR, o.name)) },
                            label = { Text(t, color = if (e.orden == o) Ambar else Color.White) },
                        )
                    }
                }
            }
            if (e.buscando) item { Text("Buscando…") }
            if (!e.buscando && e.hoteles.isEmpty()) item {
                Text("Sin resultados. Pide la búsqueda en el teléfono o toca abajo.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            }
            e.hoteles.forEach { h ->
                item(key = h.id) {
                    Boton(
                        h.nombre,
                        "$${h.precioEstimado} · ${Geo.textoDistancia(h.distanciaM)}" + if (h.estrellas > 0) " · ${h.estrellas}★" else "",
                        if (h.id == e.hotelElegido) Ambar.copy(alpha = 0.35f) else null,
                    ) {
                        enviar(Comando(TipoComando.ELEGIR_HOTEL, h.id))
                        verRutas()
                    }
                }
            }
            item {
                CompactButton(onClick = { enviar(Comando(TipoComando.BUSCAR_HOTELES, "hotel barato cerca")) }, label = { Text("Buscar de nuevo") })
            }
        }
    }
}

@Composable
fun PantallaRutas(e: EstadoReloj, enviar: (Comando) -> Unit, verMapa: () -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    val hotel = e.hoteles.firstOrNull { it.id == e.hotelElegido }
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text(hotel?.nombre ?: "Rutas", maxLines = 2) } }
            if (e.buscando) item { Text("Calculando…") }
            if (e.mapa != null) {
                item { CompactButton(onClick = verMapa, label = { Text("🗺 Ver en mapa", color = Ambar) }) }
            }
            e.rutas.forEachIndexed { i, r ->
                item(key = "r$i") {
                    val resumen = r.tramos.joinToString(" ") { t ->
                        when (t.modo) {
                            Modo.CAMINAR -> "🚶${t.minutos}"
                            Modo.TAXI -> "🚕"
                            Modo.METRO -> "🚇${t.linea.substringAfter(' ')}"
                            else -> "🚌${t.linea.substringAfter(' ', "")}"
                        }
                    }
                    val total = r.costo + (hotel?.precioEstimado ?: 0)
                    Boton("${r.minutos} min · $${r.costo.roundToInt()}", "$resumen\nTotal+noche ≈ $${total.roundToInt()}") {
                        enviar(Comando(TipoComando.INICIAR_NAV, i.toString()))
                    }
                }
            }
            if (e.rutas.isNotEmpty()) item {
                Text("Tiempos estimados", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun PantallaNav(e: EstadoReloj, enviar: (Comando) -> Unit, conectado: Boolean, verMapa: () -> Unit) {
    val n = e.nav
    val lista = rememberTransformingLazyColumnState()
    if (e.soloVibracion && n != null) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { enviar(Comando(TipoComando.SOLO_VIBRACION)) })
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(Geo.textoDistancia(n.metrosAlGiro), color = Color.DarkGray, fontSize = 14.sp)
        }
        return
    }
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            if (n == null) {
                val info = e.alerta?.takeIf { it.tipo == TipoAlerta.INFO }?.texto
                item { ListHeader { Text(if (info != null) "✓" else "Sin navegación", color = if (info != null) Verde else Color.White) } }
                item { Text(info ?: "Elige una ruta en el reloj o en el teléfono", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
                return@TransformingLazyColumn
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Flecha(if (n.giro == Giro.LLEGADA) 0 else n.flechaGrados, Modifier.size(64.dp))
                    Text(Geo.textoDistancia(n.metrosAlGiro), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(textoGiro(n.giro) + " · " + n.calle, textAlign = TextAlign.Center, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
            n.indicacion?.let { item { Text(it, color = Ambar, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) } }
            item {
                Text(
                    "${Geo.textoDistancia(n.metrosRestantes)} · ${n.minutosRestantes} min" + if (!conectado) "\n(sin teléfono: última ruta)" else "",
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            item { CompactButton(onClick = verMapa, label = { Text("🗺 Ver mapa", color = Ambar) }) }
            item { CompactButton(onClick = { enviar(Comando(TipoComando.SOLO_VIBRACION)) }, label = { Text("Solo vibración") }) }
            item { CompactButton(onClick = { enviar(Comando(TipoComando.RECALCULAR)) }, label = { Text("Recalcular") }) }
            item { CompactButton(onClick = { enviar(Comando(TipoComando.DETENER_NAV)) }, label = { Text("Detener") }) }
            item { BotonSos { enviar(Comando(TipoComando.PANICO)) } }
        }
    }
}

/** Mapa vectorial para la pantalla circular del reloj: muestra la ruta, hoteles, tu posición y el destino. */
@Composable
fun PantallaMapaReloj(e: EstadoReloj) {
    val m = e.mapa
    val puntos = remember(m, e.hoteles) {
        buildList {
            m?.yo?.let { add(it) }
            m?.destino?.let { add(it) }
            m?.trazos?.forEach { addAll(it.puntos) }
            if (isEmpty()) addAll(e.hoteles.map { it.punto })
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF101826)), contentAlignment = Alignment.Center) {
        if (puntos.isEmpty()) {
            Text("Busca un hotel para ver el mapa", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(18.dp))
            return@Box
        }
        val minLat = puntos.minOf { it.lat }
        val maxLat = puntos.maxOf { it.lat }
        val minLon = puntos.minOf { it.lon }
        val maxLon = puntos.maxOf { it.lon }
        val cLat = (minLat + maxLat) / 2.0
        val cLon = (minLon + maxLon) / 2.0
        val cosLat = cos(Math.toRadians(cLat)).coerceAtLeast(0.5)
        val spanLon = ((maxLon - minLon) * cosLat).coerceAtLeast(0.004)
        val spanLat = (maxLat - minLat).coerceAtLeast(0.004)
        val span = maxOf(spanLon, spanLat) * 1.45

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val escala = minOf(w, h) / span.toFloat()
            fun aXY(p: Punto): Offset {
                val x = w / 2f + ((p.lon - cLon) * cosLat).toFloat() * escala
                val y = h / 2f - (p.lat - cLat).toFloat() * escala
                return Offset(x, y)
            }

            // Cuadrícula de referencia en la esfera del reloj
            val paso = w / 5f
            for (i in 1..4) {
                drawLine(Color(0xFF1E2B42), Offset(paso * i, 0f), Offset(paso * i, h), 1f)
                drawLine(Color(0xFF1E2B42), Offset(0f, paso * i), Offset(w, paso * i), 1f)
            }

            // Trazos de la ruta
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
                    val color = when (tr.modo) {
                        Modo.CAMINAR -> Ambar
                        Modo.TAXI -> Color(0xFFA855F7)
                        Modo.METRO -> Color(0xFFFF6B35)
                        else -> AzulRuta
                    }
                    val efecto = if (tr.modo == Modo.CAMINAR) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null
                    drawPath(path, Color.Black.copy(alpha = 0.7f), style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = efecto))
                }
            }

            // Hoteles cercanos
            e.hoteles.forEach { hotel ->
                val o = aXY(hotel.punto)
                drawCircle(Ambar.copy(alpha = 0.75f), radius = 4.5f, center = o)
            }

            // Destino (verde)
            m?.destino?.let { dest ->
                val o = aXY(dest)
                drawCircle(Color.White, radius = 9f, center = o)
                drawCircle(Verde, radius = 6.5f, center = o)
            }

            // Tu ubicación (azul)
            m?.yo?.let { yo ->
                val o = aXY(yo)
                drawCircle(Color(0x552563EB), radius = 14f, center = o)
                drawCircle(Color.White, radius = 7.5f, center = o)
                drawCircle(Color(0xFF2563EB), radius = 5.5f, center = o)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(vertical = 10.dp, horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                m?.destinoNombre?.ifBlank { e.ciudad.ifBlank { "Mapa" } } ?: "Mapa",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ambar, maxLines = 1,
            )
            Text("🔵 Tú  🟢 Destino  🟦 Ruta", fontSize = 9.sp, color = Color.LightGray)
        }
    }
}

@Composable
fun PantallaAlerta(texto: String, pide: Boolean, tipo: TipoAlerta, ok: () -> Unit, sos: () -> Unit) {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text(if (tipo == TipoAlerta.RIESGO) "⚠ Aviso" else "Sereno", color = if (tipo == TipoAlerta.RIESGO) Ambar else Color.White) } }
            item { Text(texto, textAlign = TextAlign.Center) }
            item { Boton(if (pide) "Estoy bien" else "OK", null, Verde) { ok() } }
            if (pide) item { Boton("Llamar 911", null, Rojo) { sos() } }
        }
    }
}

@Composable
fun PantallaAyuda() {
    val lista = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = lista) { padding ->
        TransformingLazyColumn(state = lista, contentPadding = padding) {
            item { ListHeader { Text("Vibraciones") } }
            listOf(
                "Izquierda: 2 toques", "Derecha: 3 toques", "Recto / leve: 1 toque",
                "Vuelta en U: largo + 2", "Llegaste: 1 larga", "Alerta: 3 largas",
                "SOS 911: mantén presionado", "Solo vibración: doble toque para salir",
            ).forEach { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

// ---------- Componentes ----------

@Composable
fun Boton(titulo: String, sub: String?, fondo: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (fondo != null) ButtonDefaults.buttonColors(containerColor = fondo, contentColor = Color.White, secondaryContentColor = Color.White.copy(alpha = 0.85f))
        else ButtonDefaults.filledTonalButtonColors(),
        label = { Text(titulo, maxLines = 2) },
        secondaryLabel = sub?.let { { Text(it, maxLines = 3) } },
    )
}

/** Mantener presionado ~0.5 s para evitar activaciones por accidente en el bolsillo. */
@Composable
fun BotonSos(activar: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(Rojo)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { activar() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text("SOS 911 (mantener)", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Flecha(grados: Int, modifier: Modifier) {
    Canvas(modifier.rotate(grados.toFloat())) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w / 2, 0f); lineTo(w, h * 0.85f); lineTo(w / 2, h * 0.65f); lineTo(0f, h * 0.85f); close()
        }
        drawPath(p, Ambar)
        drawCircle(Color.White, radius = w * 0.06f, center = Offset(w / 2, h * 0.65f))
    }
}

fun textoGiro(g: Giro) = when (g) {
    Giro.SALIDA -> "Sal"; Giro.RECTO -> "Sigue recto"; Giro.LIGERA_IZQ -> "Ligera izq."; Giro.IZQ -> "Gira izq."
    Giro.FUERTE_IZQ -> "Fuerte izq."; Giro.LIGERA_DER -> "Ligera der."; Giro.DER -> "Gira der."; Giro.FUERTE_DER -> "Fuerte der."
    Giro.VUELTA_U -> "Vuelta en U"; Giro.ROTONDA -> "Glorieta"; Giro.LLEGADA -> "Llegas"
}
