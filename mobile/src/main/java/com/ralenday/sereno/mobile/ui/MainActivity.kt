package com.ralenday.sereno.mobile.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ralenday.sereno.core.Enlace
import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Hotel
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Orden
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.Ruta
import com.ralenday.sereno.core.TipoAlerta
import com.ralenday.sereno.mobile.datos.Ciudades
import com.ralenday.sereno.mobile.datos.NivelRiesgo
import com.ralenday.sereno.mobile.datos.Osm
import com.ralenday.sereno.mobile.logica.EstadoApp
import com.ralenday.sereno.mobile.logica.Motor
import com.ralenday.sereno.mobile.logica.ServicioViaje
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

val Ambar = Color(0xFFFCA311)
val Rojo = Color(0xFFE5484D)
val Verde = Color(0xFF2E9E5B)
val AzulRuta = Color(0xFF00B8F0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ambar, secondary = Ambar)) { AppTelefono() }
        }
    }
}

@Composable
fun AppTelefono() {
    val motor = remember { Motor.get() }
    val e by motor.estado.collectAsState()
    var pestana by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) ServicioViaje.iniciar(context)
    }
    LaunchedEffect(Unit) {
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) lista += Manifest.permission.POST_NOTIFICATIONS
        permisos.launch(lista.toTypedArray())
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("🧭" to "Viaje", "🛡" to "Seguridad", "⌚" to "Reloj").forEachIndexed { i, (ic, t) ->
                    NavigationBarItem(selected = pestana == i, onClick = { pestana = i }, icon = { Text(ic) }, label = { Text(t) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (pestana) {
                0 -> PantallaViaje(e, motor)
                1 -> PantallaSeguridad(e, motor)
                else -> PantallaReloj(e)
            }
        }
    }

    e.alerta?.let { a ->
        AlertDialog(
            onDismissRequest = { motor.cerrarAlerta() },
            title = { Text(if (a.tipo == TipoAlerta.RIESGO) "⚠ Aviso" else "Sereno") },
            text = { Text(a.texto) },
            confirmButton = {
                TextButton(onClick = { if (a.pideRespuesta) motor.estoyBien() else motor.cerrarAlerta() }) {
                    Text(if (a.pideRespuesta) "Estoy bien" else "OK")
                }
            },
            dismissButton = if (a.pideRespuesta) ({ TextButton(onClick = { motor.panico() }) { Text("Llamar 911", color = Rojo) } }) else null,
        )
    }
}

// =================== VIAJE ===================

@Composable
fun PantallaViaje(e: EstadoApp, motor: Motor) {
    var texto by rememberSaveable { mutableStateOf("Necesito hotel barato cerca del centro y transporte público") }
    val voz = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { texto = it; motor.buscar(it) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Sereno", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Compañero de viaje discreto", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(e.fuenteUbicacion == "gps", { motor.usarFuente("gps") }, { Text("📍 GPS") })
                Ciudades.todas.forEach { c ->
                    FilterChip(e.fuenteUbicacion == c.id, { motor.usarFuente(c.id) }, { Text("${c.nombre} (${c.tamano.lowercase()})") })
                }
            }
            Text(
                buildString {
                    append(e.ciudad?.nombre ?: "Ciudad no detectada")
                    e.ubicacion?.let { append(" · %.4f, %.4f".format(it.lat, it.lon)) }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            e.riesgo?.let { r ->
                val color = when (r.nivel) { NivelRiesgo.ALTO -> Rojo; NivelRiesgo.MEDIO -> Ambar; NivelRiesgo.BAJO -> Verde; else -> Color.Gray }
                Text("Zona: ${r.nivel.name.lowercase()} — ${r.motivo}", color = color, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(texto, { texto = it }, Modifier.fillMaxWidth(), label = { Text("¿Qué necesitas?") }, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { motor.buscar(texto) }, enabled = !e.buscando) { Text("Buscar") }
                OutlinedButton(onClick = {
                    voz.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Ej.: hostal barato cerca del Zócalo")
                    })
                }) { Text("🎤 Dictar") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Hostal barato", "Hotel mejor valorado cerca", "Motel menos de 800", "Hotel cerca del centro").forEach { s ->
                    OutlinedButton(onClick = { texto = s; motor.buscar(s) }) { Text(s, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (e.buscando || e.calculandoRutas) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        e.mensaje?.let { m -> item { Text(m, style = MaterialTheme.typography.bodySmall, color = Ambar) } }

        // Mapa interactivo (teléfono): muestra tu ubicación, los hoteles encontrados y la ruta activa/seleccionada
        if (e.ubicacion != null || e.hoteles.isNotEmpty() || e.rutas.isNotEmpty()) {
            item(key = "mapa_telefono") {
                val rutaVisible = e.rutaActiva?.let { e.rutas.getOrNull(it) } ?: e.rutas.getOrNull(e.rutaSeleccionada)
                TarjetaMapaTelefono(
                    ubicacion = e.ubicacion ?: e.ciudad?.centro,
                    hoteles = e.hoteles,
                    hotelElegido = e.hotelElegido,
                    ruta = rutaVisible,
                    alElegirHotel = { motor.elegirHotel(it.id) },
                )
            }
        }

        e.nav?.let { n ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A44))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Navegando a ${n.destino}", fontWeight = FontWeight.Bold)
                        Text("${flechaTexto(n.flechaGrados)}  ${Geo.textoDistancia(n.metrosAlGiro)} → ${n.giro.name.lowercase().replace('_', ' ')} en ${n.calle}")
                        n.indicacion?.let { Text(it, color = Ambar) }
                        Text("Faltan ${Geo.textoDistancia(n.metrosRestantes)} · ~${n.minutosRestantes} min", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { motor.simular(!e.simulando) }) { Text(if (e.simulando) "Pausar simulación" else "Simular caminata") }
                            OutlinedButton(onClick = { motor.detenerNav() }) { Text("Detener") }
                        }
                    }
                }
            }
        }

        if (e.hotelElegido != null && e.rutas.isNotEmpty()) {
            item {
                val h = e.hotelElegido
                Text("Cómo llegar a ${h.nombre}", style = MaterialTheme.typography.titleMedium)
                e.ciudad?.tarifaDescuento?.let { desc ->
                    val gen = e.ciudad.tarifas[Modo.MICRO]?.roundToInt() ?: 14
                    Text(
                        "Combi en ${e.ciudad.nombre}: $$gen general · $${desc.roundToInt()} estudiante y adulto mayor (parada en cualquier punto de la ruta). " +
                            "Taxi desde $${e.ciudad.taxiBanderazo.roundToInt()} según la zona.",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    )
                }
            }
            e.rutas.forEachIndexed { i, r ->
                item(key = "ruta$i") {
                    TarjetaRuta(
                        r = r,
                        precioHotel = e.hotelElegido.precioEstimado,
                        activa = e.rutaActiva == i || (e.rutaActiva == null && e.rutaSeleccionada == i),
                        seleccionar = { motor.seleccionarRuta(i) },
                        iniciar = { motor.iniciarNav(i) },
                    )
                }
            }
        }

        if (e.hoteles.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Alojamiento", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    listOf(Orden.DISTANCIA to "Cerca", Orden.PRECIO to "$", Orden.VALORACION to "★").forEach { (o, t) ->
                        FilterChip(e.orden == o, { motor.ordenar(o) }, { Text(t) })
                    }
                }
            }
            e.hoteles.forEach { h ->
                item(key = h.id) { TarjetaHotel(h, elegido = e.hotelElegido?.id == h.id) { motor.elegirHotel(h.id) } }
            }
            item {
                Text(
                    "Alojamiento y mapa: © colaboradores de OpenStreetMap (ODbL). Precios ESTIMADOS por tipo y estrellas; " +
                        "no hay API gratuita de precios reales. Confirma al llegar.",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// =================== MAPA OSM (TELÉFONO) ===================

private fun lonAX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
private fun latAY(lat: Double, z: Int): Double {
    val rad = lat * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z)
}

@Composable
fun TarjetaMapaTelefono(
    ubicacion: Punto?,
    hoteles: List<Hotel>,
    hotelElegido: Hotel?,
    ruta: Ruta?,
    alElegirHotel: (Hotel) -> Unit,
) {
    val context = LocalContext.current
    val teselas = remember { mutableStateMapOf<String, ImageBitmap>() }
    var zoomAjuste by remember { mutableIntStateOf(0) }

    // Puntos que encuadra el mapa
    val puntosRuta = remember(ruta) {
        ruta?.tramos?.flatMap { it.trazo.ifEmpty { listOfNotNull(it.desdePunto, it.hastaPunto) } }.orEmpty()
    }
    val puntosEncuadre = remember(ubicacion, hotelElegido, puntosRuta, hoteles) {
        buildList {
            ubicacion?.let { add(it) }
            if (hotelElegido != null) {
                add(hotelElegido.punto)
                addAll(puntosRuta)
            } else {
                addAll(hoteles.take(6).map { it.punto })
            }
        }.ifEmpty { listOf(Ciudades.SLRC.centro) }
    }

    val minLat = puntosEncuadre.minOf { it.lat }
    val maxLat = puntosEncuadre.maxOf { it.lat }
    val minLon = puntosEncuadre.minOf { it.lon }
    val maxLon = puntosEncuadre.maxOf { it.lon }
    val centro = Punto((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    val span = maxOf(maxLat - minLat, maxLon - minLon)
    val zoomBase = when {
        span > 0.12 -> 11
        span > 0.06 -> 12
        span > 0.03 -> 13
        span > 0.014 -> 14
        else -> 15
    }
    val z = (zoomBase + zoomAjuste).coerceIn(11, 17)
    val cx = lonAX(centro.lon, z)
    val cy = latAY(centro.lat, z)

    // Descarga teselas de OpenStreetMap en segundo plano (con caché en disco)
    LaunchedEffect(z, floor(cx).toInt(), floor(cy).toInt()) {
        val tx0 = floor(cx).toInt()
        val ty0 = floor(cy).toInt()
        val n = 1 shl z
        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = (tx0 + dx).coerceIn(0, n - 1)
                val ty = (ty0 + dy).coerceIn(0, n - 1)
                val clave = "$z/$tx/$ty"
                if (clave in teselas) continue
                withContext(Dispatchers.IO) {
                    try {
                        val dir = File(context.cacheDir, "osm_tiles/$z/$tx").apply { mkdirs() }
                        val archivo = File(dir, "$ty.png")
                        if (!archivo.exists()) {
                            val c = URL("https://tile.openstreetmap.org/$clave.png").openConnection() as HttpURLConnection
                            c.connectTimeout = 8_000; c.readTimeout = 8_000
                            c.setRequestProperty("User-Agent", "Sereno/0.1 (Android; proyecto escolar)")
                            try {
                                if (c.responseCode in 200..299) {
                                    c.inputStream.use { inp -> archivo.outputStream().use { out -> inp.copyTo(out) } }
                                }
                            } finally { c.disconnect() }
                        }
                        if (archivo.exists()) {
                            BitmapFactory.decodeFile(archivo.absolutePath)?.asImageBitmap()?.let { bmp ->
                                teselas[clave] = bmp
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141B29)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (hotelElegido != null) "Mapa: ruta a ${hotelElegido.nombre}" else "Mapa de alojamiento y rutas",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { zoomAjuste = (zoomAjuste - 1).coerceAtLeast(-3) }) { Text("−") }
                TextButton(onClick = { zoomAjuste = (zoomAjuste + 1).coerceAtMost(3) }) { Text("+") }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1B2436))
                    .pointerInput(hoteles, z, cx, cy) {
                        detectTapGestures { tap ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            hoteles.minByOrNull { hotel ->
                                val px = (w / 2f + (lonAX(hotel.punto.lon, z) - cx) * 256.0).toFloat()
                                val py = (h / 2f + (latAY(hotel.punto.lat, z) - cy) * 256.0).toFloat()
                                (px - tap.x) * (px - tap.x) + (py - tap.y) * (py - tap.y)
                            }?.let { cercano ->
                                val px = (w / 2f + (lonAX(cercano.punto.lon, z) - cx) * 256.0).toFloat()
                                val py = (h / 2f + (latAY(cercano.punto.lat, z) - cy) * 256.0).toFloat()
                                if ((px - tap.x) * (px - tap.x) + (py - tap.y) * (py - tap.y) <= 42f * 42f) {
                                    alElegirHotel(cercano)
                                }
                            }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    fun aPantalla(p: Punto): Offset {
                        val x = w / 2f + ((lonAX(p.lon, z) - cx) * 256.0).toFloat()
                        val y = h / 2f + ((latAY(p.lat, z) - cy) * 256.0).toFloat()
                        return Offset(x, y)
                    }

                    // 1) Teselas de OpenStreetMap (o cuadrícula de respaldo si no hay red)
                    val tx0 = floor(cx).toInt()
                    val ty0 = floor(cy).toInt()
                    var algunaTesela = false
                    for (dx in -2..2) {
                        for (dy in -2..2) {
                            val tx = tx0 + dx
                            val ty = ty0 + dy
                            val bmp = teselas["$z/$tx/$ty"] ?: continue
                            algunaTesela = true
                            val ox = (w / 2f + (tx - cx) * 256.0).roundToInt()
                            val oy = (h / 2f + (ty - cy) * 256.0).roundToInt()
                            drawImage(bmp, dstOffset = IntOffset(ox, oy), dstSize = IntSize(256, 256), alpha = 0.88f)
                        }
                    }
                    if (!algunaTesela) {
                        val paso = 48f
                        var x = 0f
                        while (x < w) { drawLine(Color(0xFF28344E), Offset(x, 0f), Offset(x, h), 1f); x += paso }
                        var y = 0f
                        while (y < h) { drawLine(Color(0xFF28344E), Offset(0f, y), Offset(w, y), 1f); y += paso }
                    }

                    // 2) Trazos de la ruta (caminar punteado, combi/metro/bus sólido azul/ámbar)
                    ruta?.tramos?.forEach { t ->
                        val pts = t.trazo.ifEmpty { listOfNotNull(t.desdePunto, t.hastaPunto) }
                        if (pts.size >= 2) {
                            val path = Path().apply {
                                val p0 = aPantalla(pts.first())
                                moveTo(p0.x, p0.y)
                                pts.drop(1).forEach { pt ->
                                    val o = aPantalla(pt)
                                    lineTo(o.x, o.y)
                                }
                            }
                            val colorTramo = when (t.modo) {
                                Modo.CAMINAR -> Ambar
                                Modo.TAXI -> Color(0xFFA855F7)
                                Modo.METRO -> Color(0xFFFF6B35)
                                else -> AzulRuta
                            }
                            val efecto = if (t.modo == Modo.CAMINAR) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null
                            drawPath(path, Color.Black.copy(alpha = 0.65f), style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawPath(path, colorTramo, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = efecto))
                        }
                    }

                    // 3) Hoteles (en gris/ámbar; el elegido en verde brillante)
                    hoteles.take(12).forEach { hotel ->
                        val pos = aPantalla(hotel.punto)
                        val esElegido = hotel.id == hotelElegido?.id
                        drawCircle(Color.Black, radius = if (esElegido) 13f else 9f, center = pos)
                        drawCircle(if (esElegido) Verde else Ambar, radius = if (esElegido) 10f else 6.5f, center = pos)
                    }

                    // 4) Tu ubicación actual (punto azul con halo blanco)
                    ubicacion?.let { yo ->
                        val pos = aPantalla(yo)
                        drawCircle(Color(0x552563EB), radius = 20f, center = pos)
                        drawCircle(Color.White, radius = 10f, center = pos)
                        drawCircle(Color(0xFF2563EB), radius = 7f, center = pos)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("🔵 Tu ubicación", style = MaterialTheme.typography.labelSmall)
                Text("🟢 Destino", style = MaterialTheme.typography.labelSmall)
                Text("🟦 Combi / transporte", style = MaterialTheme.typography.labelSmall)
                Text("🟨 Caminar", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun TarjetaHotel(h: Hotel, elegido: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (elegido) Color(0xFF3A2E10) else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(h.nombre, fontWeight = FontWeight.Bold)
            Text(
                "${Osm.NOMBRE_TIPO[h.tipo] ?: h.tipo} · ≈ $${h.precioEstimado}/noche · ${Geo.textoDistancia(h.distanciaM)}" +
                    if (h.estrellas > 0) " · " + "★".repeat(h.estrellas) else "",
                style = MaterialTheme.typography.bodySmall,
            )
            listOfNotNull(h.direccion, h.telefono).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun TarjetaRuta(r: Ruta, precioHotel: Int, activa: Boolean, seleccionar: () -> Unit, iniciar: () -> Unit) {
    Card(
        onClick = seleccionar,
        colors = CardDefaults.cardColors(containerColor = if (activa) Color(0xFF1F3A2A) else MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.minutos} min · $${"%.2f".format(r.costo)} · ${r.transbordos} transbordo(s)", fontWeight = FontWeight.Bold)
                    Text(r.titulo, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = iniciar, colors = ButtonDefaults.buttonColors(containerColor = Ambar)) { Text("Ir", color = Color.Black) }
            }
            r.tramos.forEach { t ->
                val icono = when (t.modo) { Modo.CAMINAR -> "🚶"; Modo.TAXI -> "🚕"; Modo.METRO -> "🚇"; Modo.CABLEBUS -> "🚡"; Modo.TREN, Modo.TREN_LIGERO -> "🚆"; else -> "🚌" }
                val linea = if (t.modo == Modo.CAMINAR) "Camina ${t.minutos} min a ${t.hasta}"
                else if (t.modo == Modo.TAXI) {
                    val detalle = if (t.linea.isNotBlank()) " · ${t.linea}" else ""
                    "Taxi/app ${t.minutos} min (≈ $${t.costo.roundToInt()}$detalle)"
                }
                else "${t.linea}: ${t.desde} → ${t.hasta} (${t.minutos} min, $${"%.2f".format(t.costo)})"
                Text("$icono $linea", style = MaterialTheme.typography.bodySmall)
            }
            Text("Total con 1 noche ≈ $${(r.costo + precioHotel).roundToInt()}", style = MaterialTheme.typography.labelSmall, color = Ambar)
        }
    }
}

fun flechaTexto(grados: Int): String {
    val g = ((grados % 360) + 360) % 360
    return listOf("⬆", "↗", "➡", "↘", "⬇", "↙", "⬅", "↖")[((g + 22) / 45) % 8]
}

// =================== SEGURIDAD ===================

@Composable
fun PantallaSeguridad(e: EstadoApp, motor: Motor) {
    val s = motor.seguridad
    var quieto by remember { mutableStateOf(s.detectarQuieto) }
    var minutos by remember { mutableStateOf(s.minutosQuieto.toFloat()) }
    var riesgo by remember { mutableStateOf(s.avisosRiesgo) }
    var historial by remember { mutableIntStateOf(s.puntosHistorial()) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text("Seguridad", style = MaterialTheme.typography.headlineSmall) }
        item {
            Fila("Preguntar si estoy bien al quedarme quieto", quieto) { quieto = it; s.detectarQuieto = it }
            Text("Tras ${minutos.roundToInt()} min sin moverte durante un trayecto (fuera del hotel). Si no respondes en 2 min se abre llamada al 911.", style = MaterialTheme.typography.bodySmall)
            Slider(minutos, { minutos = it }, valueRange = 1f..30f, onValueChangeFinished = { s.minutosQuieto = minutos.roundToInt() })
            Fila("Avisos de zona de riesgo", riesgo) { riesgo = it; s.avisosRiesgo = it }
        }
        item {
            Button(onClick = { motor.panico() }, colors = ButtonDefaults.buttonColors(containerColor = Rojo), modifier = Modifier.fillMaxWidth()) {
                Text("SOS · MARCAR 911 (en el reloj: mantener presionado)")
            }
            OutlinedButton(onClick = { motor.reportarZona() }, modifier = Modifier.fillMaxWidth()) { Text("Marcar mi ubicación como zona insegura") }
        }
        item {
            Text("Historial de ruta guardado en el teléfono: $historial puntos", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { s.borrarHistorial(); historial = 0 }) { Text("Borrar historial") }
            Text(
                "Riesgo en CDMX: carpetas de investigación FGJ ${com.ralenday.sereno.mobile.datos.Riesgo.ANIO_DATOS} (datos.cdmx.gob.mx, coordenadas aproximadas). " +
                    "En San Luis R.C. no hay delitos geolocalizados abiertos: se usan tus reportes y la hora. Todo es orientativo.",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun Fila(texto: String, valor: Boolean, cambiar: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(texto, Modifier.weight(1f))
        Switch(valor, cambiar)
    }
}

// =================== RELOJ ===================

@Composable
fun PantallaReloj(e: EstadoApp) {
    val context = LocalContext.current
    val enlace = remember { Enlace.get(context) }
    val conectado by enlace.conectado.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Reloj", style = MaterialTheme.typography.headlineSmall)
            Text(if (conectado) "● Reloj conectado" else "○ Reloj no conectado", color = if (conectado) Verde else Rojo)
            if (enlace.esEmulador) Text("Emulador: puente TCP en puertos ${Enlace.PUERTO_TELEFONO}/${Enlace.PUERTO_RELOJ} (ver EMULADORES.md)", style = MaterialTheme.typography.labelSmall)
        }
        item {
            Text("Lo que ve el reloj ahora", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    e.nav != null -> "Navegación: ${flechaTexto(e.nav.flechaGrados)} ${Geo.textoDistancia(e.nav.metrosAlGiro)} · ${e.nav.calle}"
                    e.rutas.isNotEmpty() -> "${e.rutas.size} rutas a ${e.hotelElegido?.nombre}"
                    e.hoteles.isNotEmpty() -> "${minOf(8, e.hoteles.size)} hoteles"
                    else -> "Inicio (sin búsqueda)"
                },
            )
            if (e.soloVibracion) Text("Modo solo vibración activado desde el reloj", color = Ambar)
        }
        item { Text("Bitácora", style = MaterialTheme.typography.titleSmall) }
        e.bitacora.forEach { item { Text(it, style = MaterialTheme.typography.labelSmall) } }
        item {
            Text(
                "Datos: OpenStreetMap (Overpass, Nominatim, teselas), OSRM de FOSSGIS (rutas a pie), datos abiertos CDMX y rutas locales de SLRC.",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray,
            )
        }
    }
}
