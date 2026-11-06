package com.ralenday.sereno.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ralenday.sereno.core.Enlace
import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Hotel
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Orden
import com.ralenday.sereno.core.Ruta
import com.ralenday.sereno.core.TipoAlerta
import com.ralenday.sereno.mobile.datos.Ciudades
import com.ralenday.sereno.mobile.datos.NivelRiesgo
import com.ralenday.sereno.mobile.datos.Osm
import com.ralenday.sereno.mobile.logica.EstadoApp
import com.ralenday.sereno.mobile.logica.Motor
import com.ralenday.sereno.mobile.logica.ServicioViaje
import kotlin.math.roundToInt

val Ambar = Color(0xFFFCA311)
val Rojo = Color(0xFFE5484D)
val Verde = Color(0xFF2E9E5B)

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
    val context = androidx.compose.ui.platform.LocalContext.current

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
            dismissButton = if (a.pideRespuesta) ({ TextButton(onClick = { motor.panico() }) { Text("Pedir ayuda", color = Rojo) } }) else null,
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
            }
            e.rutas.forEachIndexed { i, r ->
                item(key = "ruta$i") { TarjetaRuta(r, e.hotelElegido.precioEstimado, activa = e.rutaActiva == i) { motor.iniciarNav(i) } }
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
                    "Alojamiento: © colaboradores de OpenStreetMap (ODbL). Precios ESTIMADOS por tipo y estrellas; " +
                        "no hay API gratuita de precios reales. Confirma al llegar.",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                )
                Spacer(Modifier.height(16.dp))
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
fun TarjetaRuta(r: Ruta, precioHotel: Int, activa: Boolean, iniciar: () -> Unit) {
    Card(
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
                else if (t.modo == Modo.TAXI) "Taxi/app ${t.minutos} min (≈ $${t.costo.roundToInt()})"
                else "${t.linea}: ${t.desde} → ${t.hasta} (${t.paradas} paradas, ${t.minutos} min, $${"%.2f".format(t.costo)})"
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
    var nombre by remember { mutableStateOf(s.nombre) }
    var contactos by remember { mutableStateOf(s.contactos.joinToString(", ")) }
    var quieto by remember { mutableStateOf(s.detectarQuieto) }
    var minutos by remember { mutableStateOf(s.minutosQuieto.toFloat()) }
    var riesgo by remember { mutableStateOf(s.avisosRiesgo) }
    var iluminadas by remember { mutableStateOf(s.preferirIluminadas) }
    var historial by remember { mutableIntStateOf(s.puntosHistorial()) }
    val permisoSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text("Seguridad", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(nombre, { nombre = it }, Modifier.fillMaxWidth(), label = { Text("Tu nombre (va en los SMS)") })
            OutlinedTextField(contactos, { contactos = it }, Modifier.fillMaxWidth(), label = { Text("Teléfonos de confianza (separados por coma)") })
            Button(onClick = {
                s.nombre = nombre
                s.contactos = contactos.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (!s.puedeEnviarSms()) permisoSms.launch(Manifest.permission.SEND_SMS)
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Guardar") }
            if (!s.puedeEnviarSms()) Text("Falta el permiso de SMS para pánico / ubicación en vivo", color = Ambar, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Fila("Compartir ubicación en vivo (SMS cada 5 min)", e.compartiendo) { motor.compartir(it) }
            Fila("Preguntar si estoy bien al quedarme quieto", quieto) { quieto = it; s.detectarQuieto = it }
            Text("Tras ${minutos.roundToInt()} min sin moverte (fuera del hotel). Si no respondes en 2 min se avisa a tus contactos.", style = MaterialTheme.typography.bodySmall)
            Slider(minutos, { minutos = it }, valueRange = 1f..30f, onValueChangeFinished = { s.minutosQuieto = minutos.roundToInt() })
            Fila("Avisos de zona de riesgo", riesgo) { riesgo = it; s.avisosRiesgo = it }
            Fila("De noche, preferir calles iluminadas / transitadas", iluminadas) { iluminadas = it; s.preferirIluminadas = it }
        }
        item {
            Button(onClick = { motor.panico() }, colors = ButtonDefaults.buttonColors(containerColor = Rojo), modifier = Modifier.fillMaxWidth()) {
                Text("BOTÓN DE PÁNICO (en el reloj: mantener presionado)")
            }
            OutlinedButton(onClick = { motor.reportarZona() }, modifier = Modifier.fillMaxWidth()) { Text("Marcar mi ubicación como zona insegura") }
        }
        item {
            Text("Historial de ruta guardado en el teléfono: $historial puntos", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { s.borrarHistorial(); historial = 0 }) { Text("Borrar historial") }
            Text(
                "Riesgo en CDMX: carpetas de investigación FGJ ${com.ralenday.sereno.mobile.datos.Riesgo.ANIO_DATOS} (datos.cdmx.gob.mx, coordenadas aproximadas). " +
                    "En Mexicali y San Luis R.C. no hay delitos geolocalizados abiertos: se usan tus reportes y la hora. " +
                    "Iluminación: etiquetas lit=* de OpenStreetMap. Todo es orientativo.",
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
                "Datos: OpenStreetMap (Overpass, Nominatim), OSRM de FOSSGIS (rutas a pie), datos abiertos CDMX. " +
                    "Todos gratuitos; respeta sus políticas de uso.",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray,
            )
        }
    }
}
