package com.ralenday.sereno.mobile.logica

import android.content.Context
import android.util.Log
import com.ralenday.sereno.core.Alerta
import com.ralenday.sereno.core.Comando
import com.ralenday.sereno.core.Enlace
import com.ralenday.sereno.core.EstadoReloj
import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Giro
import com.ralenday.sereno.core.Hotel
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.NavActual
import com.ralenday.sereno.core.Orden
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.Ruta
import com.ralenday.sereno.core.TipoAlerta
import com.ralenday.sereno.core.TipoComando
import com.ralenday.sereno.core.Tramo
import com.ralenday.sereno.core.aHotel
import com.ralenday.sereno.core.aJson
import com.ralenday.sereno.core.aRuta
import com.ralenday.sereno.core.objetos
import com.ralenday.sereno.mobile.datos.Ciudad
import com.ralenday.sereno.mobile.datos.Ciudades
import com.ralenday.sereno.mobile.datos.EvaluacionRiesgo
import com.ralenday.sereno.mobile.datos.NivelRiesgo
import com.ralenday.sereno.mobile.datos.Osm
import com.ralenday.sereno.mobile.datos.Riesgo
import com.ralenday.sereno.mobile.datos.Ruteo
import com.ralenday.sereno.mobile.datos.RutaPie
import com.ralenday.sereno.mobile.datos.Transporte
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt

data class EstadoApp(
    val ciudad: Ciudad? = null,
    /** "gps" o el id de una ciudad para simular que estás en su centro. */
    val fuenteUbicacion: String = "gps",
    val ubicacion: Punto? = null,
    val peticion: String = "",
    val buscando: Boolean = false,
    val hoteles: List<Hotel> = emptyList(),
    val orden: Orden = Orden.DISTANCIA,
    val hotelElegido: Hotel? = null,
    val calculandoRutas: Boolean = false,
    val rutas: List<Ruta> = emptyList(),
    val rutaActiva: Int? = null,
    val nav: NavActual? = null,
    val riesgo: EvaluacionRiesgo? = null,
    val alerta: Alerta? = null,
    val soloVibracion: Boolean = false,
    val compartiendo: Boolean = false,
    val simulando: Boolean = false,
    val mensaje: String? = null,
    val bitacora: List<String> = emptyList(),
)

/**
 * El cerebro del teléfono: busca, calcula rutas, sigue el GPS, vigila la seguridad y le manda
 * al reloj un resumen. Todas las mutaciones corren en un solo hilo lógico ([hilo]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Motor private constructor(private val context: Context, private val scope: CoroutineScope) {

    private val hilo = Dispatchers.Default.limitedParallelism(1)
    private val enlace = Enlace.get(context)
    private val osm = Osm(context.filesDir, context.assets)
    private val riesgo = Riesgo(osm, context.filesDir)
    val seguridad = Seguridad(context)
    private val archivoCache = File(context.filesDir, "ultima_busqueda.json")

    private val _estado = MutableStateFlow(EstadoApp())
    val estado: StateFlow<EstadoApp> = _estado

    // --- Navegación (interno) ---
    private var tramos: List<Tramo> = emptyList()
    private var tramoIdx = 0
    private var pie: RutaPie? = null
    private var pasoIdx = 1
    private var fueraDeRuta = 0
    private var avisoBajarse = 0
    private var ultimaPos: Punto? = null
    private var rumbo: Double? = null

    // --- Seguridad (interno) ---
    private var anclaQuieto: Punto? = null
    private var tiempoAncla = System.currentTimeMillis()
    private var ultimoRiesgoPos: Punto? = null
    private var jobCompartir: Job? = null
    private var jobRespuesta: Job? = null
    private var jobSimulacion: Job? = null

    fun iniciar() {
        cargarCache()
        scope.launch { enlace.comandos.collect { atender(it) } }
        // Publica al reloj cada cambio, como máximo cada 1 s (ahorra batería del reloj)
        scope.launch {
            var anterior: EstadoReloj? = null
            _estado.collect { e ->
                val r = paraReloj(e)
                if (r.copy(generado = 0) != anterior?.copy(generado = 0)) {
                    anterior = r
                    enlace.publicar(r)
                    delay(1_000)
                }
            }
        }
    }

    private fun lanzar(bloque: suspend () -> Unit) = scope.launch(hilo) {
        try { bloque() } catch (e: Exception) {
            Log.w(TAG, "Error", e)
            _estado.update { it.copy(mensaje = "Error: ${e.message}", buscando = false, calculandoRutas = false) }
        }
    }

    // ================= Entradas =================

    fun atender(c: Comando) {
        anotar("Reloj: ${c.tipo}${c.arg?.let { " ($it)" } ?: ""}")
        when (c.tipo) {
            TipoComando.PEDIR_ESTADO -> lanzar { enlace.publicar(paraReloj(_estado.value)) }
            TipoComando.BUSCAR_HOTELES -> buscar(c.arg ?: "hotel barato cerca")
            TipoComando.ORDENAR -> ordenar(Orden.valueOf(c.arg ?: Orden.DISTANCIA.name))
            TipoComando.ELEGIR_HOTEL -> c.arg?.let { elegirHotel(it) }
            TipoComando.ELEGIR_RUTA, TipoComando.INICIAR_NAV -> iniciarNav(c.arg?.toIntOrNull() ?: 0)
            TipoComando.DETENER_NAV -> detenerNav()
            TipoComando.RECALCULAR -> recalcular()
            TipoComando.PANICO -> panico()
            TipoComando.ESTOY_BIEN -> estoyBien()
            TipoComando.SOLO_VIBRACION -> _estado.update { it.copy(soloVibracion = !it.soloVibracion) }
            TipoComando.COMPARTIR_UBICACION -> compartir(!_estado.value.compartiendo)
        }
    }

    fun usarFuente(fuente: String) = lanzar {
        val ciudad = Ciudades.porId(fuente)
        if (ciudad != null) {
            detenerSimulacion()
            _estado.update { it.copy(fuenteUbicacion = fuente, ciudad = ciudad, ubicacion = ciudad.centro, riesgo = null, mensaje = "Ubicación simulada: centro de ${ciudad.nombre}") }
            ultimoRiesgoPos = null
            vigilarRiesgo(ciudad.centro)
        } else {
            _estado.update { it.copy(fuenteUbicacion = "gps", riesgo = null, mensaje = "Usando GPS") }
            ultimoRiesgoPos = null
        }
    }

    /** Llega desde [ServicioViaje] (GPS real) o del simulador. */
    fun nuevaUbicacion(p: Punto, rumboGps: Double? = null, simulada: Boolean = false) = lanzar {
        val e = _estado.value
        // Mientras se simula, o con ciudad simulada, se ignora el GPS real
        if (!simulada && (e.fuenteUbicacion != "gps" || e.simulando)) return@lanzar
        val anterior = ultimaPos
        rumbo = rumboGps ?: if (anterior != null && Geo.distancia(anterior, p) > 4) Geo.rumbo(anterior, p) else rumbo
        ultimaPos = p
        val ciudad = Ciudades.detectar(p) ?: e.ciudad
        if (ciudad != null && ciudad != e.ciudad) {
            scope.launch(Dispatchers.IO) { osm.refrescarSiHaceFalta(ciudad) }
            ultimoRiesgoPos = null
            _estado.update { it.copy(riesgo = null) }
        }
        _estado.update { it.copy(ubicacion = p, ciudad = ciudad) }
        seguridad.registrar(p)
        avanzarNav(p)
        vigilarQuieto(p)
        vigilarRiesgo(p)
    }

    fun buscar(frase: String) = lanzar {
        val e = _estado.value
        val ciudad = e.ciudad ?: e.ubicacion?.let { Ciudades.detectar(it) }
        if (ciudad == null) {
            _estado.update { it.copy(mensaje = "Elige una ciudad o activa el GPS (estás fuera de las ciudades soportadas)") }
            return@lanzar
        }
        val pet = Interprete.interpretar(frase)
        _estado.update { it.copy(peticion = frase, buscando = true, mensaje = "Buscando alojamiento en ${ciudad.nombre}…") }
        val (centro, descripcion) = withContext(Dispatchers.IO) {
            when {
                pet.cercaDe != null -> osm.buscarLugar(pet.cercaDe, ciudad)?.let { it.second to "cerca de ${it.first}" }
                    ?: ((e.ubicacion ?: ciudad.centro) to "cerca de ti (no encontré \"${pet.cercaDe}\")")
                pet.cercaDelCentro -> ciudad.centro to "cerca del centro"
                else -> (e.ubicacion ?: ciudad.centro) to "cerca de ti"
            }
        }
        var lista = withContext(Dispatchers.IO) { osm.hoteles(ciudad, centro, 2_500) }
        if (lista.size < 3) lista = withContext(Dispatchers.IO) { osm.hoteles(ciudad, centro, 7_000) }
        val desde = e.ubicacion ?: centro
        lista = lista.map { it.copy(distanciaM = Geo.distancia(desde, it.punto).roundToInt()) }
        var nota = ""
        if (pet.tipos.isNotEmpty()) {
            val filtrados = lista.filter { it.tipo in pet.tipos }
            if (filtrados.isNotEmpty()) lista = filtrados else nota = " (no hubo del tipo pedido; muestro todos)"
        }
        val tope = pet.presupuestoMax ?: if (pet.barato) ciudad.preciosNoche.getValue("hotel") else null
        if (tope != null) {
            val filtrados = lista.filter { it.precioEstimado <= tope }
            if (filtrados.isNotEmpty()) lista = filtrados else nota += " (ninguno bajo $$tope)"
        }
        _estado.update {
            it.copy(
                buscando = false, hoteles = ordenarLista(lista, pet.orden), orden = pet.orden,
                hotelElegido = null, rutas = emptyList(),
                mensaje = "${lista.size} opciones $descripcion$nota. Precios estimados.",
            )
        }
        guardarCache()
    }

    fun ordenar(orden: Orden) = lanzar {
        _estado.update { it.copy(orden = orden, hoteles = ordenarLista(it.hoteles, orden)) }
    }

    fun elegirHotel(id: String) = lanzar {
        val e = _estado.value
        val hotel = e.hoteles.firstOrNull { it.id == id } ?: return@lanzar
        val ciudad = e.ciudad ?: return@lanzar
        val origen = e.ubicacion ?: ciudad.centro
        _estado.update { it.copy(hotelElegido = hotel, calculandoRutas = true, rutas = emptyList(), mensaje = "Calculando cómo llegar a ${hotel.nombre}…") }
        // Fase 1 (instantánea): líneas incluidas en la app
        val fijas = withContext(Dispatchers.IO) { osm.lineasFijas(ciudad) }
        mostrarRutas(ciudad, origen, hotel, fijas, final = false)
        // Fase 2 (Overpass, puede tardar o fallar): camiones directos; se agregan si aparecen
        val camiones = withContext(Dispatchers.IO) {
            try { osm.lineasDeCamion(origen, hotel.punto) } catch (ex: Exception) { Log.w(TAG, "Camiones", ex); emptyList() }
        }
        if (_estado.value.hotelElegido?.id != hotel.id) return@lanzar
        mostrarRutas(ciudad, origen, hotel, fijas + camiones, final = true)
        guardarCache()
    }

    private fun mostrarRutas(ciudad: Ciudad, origen: Punto, hotel: Hotel, lineas: List<com.ralenday.sereno.mobile.datos.Linea>, final: Boolean) {
        val rutas = Transporte(ciudad).planear(origen, hotel.punto, lineas)
        val sinTransporte = rutas.none { r -> r.tramos.any { it.modo != Modo.CAMINAR && it.modo != Modo.TAXI } }
        val extra = if (final) "" else " Buscando camiones…"
        _estado.update {
            // Si ya se está navegando, no se cambian las rutas debajo del usuario
            if (it.rutaActiva != null) it.copy(calculandoRutas = false)
            else it.copy(
                calculandoRutas = !final, rutas = rutas,
                mensaje = (if (sinTransporte) "Sin transporte público con datos abiertos para este trayecto en ${ciudad.nombre}: caminar/taxi."
                else "${rutas.size} opciones. Tiempos estimados (sin datos en tiempo real).") + extra,
            )
        }
    }

    // ================= Navegación =================

    fun iniciarNav(indice: Int) = lanzar {
        val e = _estado.value
        val ruta = e.rutas.getOrNull(indice) ?: return@lanzar
        tramos = ruta.tramos
        tramoIdx = 0
        _estado.update { it.copy(rutaActiva = indice) }
        ServicioViaje.iniciar(context)
        comenzarTramo()
    }

    fun detenerNav() = lanzar {
        detenerSimulacion()
        tramos = emptyList(); pie = null
        _estado.update { it.copy(nav = null, rutaActiva = null, mensaje = "Navegación detenida") }
    }

    fun recalcular() = lanzar {
        if (tramos.isEmpty()) { _estado.value.hotelElegido?.let { elegirHotel(it.id) }; return@lanzar }
        comenzarTramo()
    }

    private suspend fun comenzarTramo() {
        val t = tramos.getOrNull(tramoIdx)
        val destinoNombre = _estado.value.hotelElegido?.nombre ?: "Destino"
        if (t == null) {
            pie = null
            detenerSimulacion()
            _estado.update { it.copy(nav = null, rutaActiva = null, alerta = alerta(TipoAlerta.INFO, "Llegaste a $destinoNombre")) }
            return
        }
        val aqui = _estado.value.ubicacion ?: t.desdePunto ?: return
        val meta = t.hastaPunto ?: return
        pasoIdx = 1; fueraDeRuta = 0; avisoBajarse = 0
        if (t.modo == Modo.CAMINAR) {
            val noche = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 19 || it < 6 }
            val opciones = withContext(Dispatchers.IO) { Ruteo.caminar(aqui, meta, alternativas = noche && seguridad.preferirIluminadas) }
            pie = if (opciones.size > 1) {
                val puntuadas = withContext(Dispatchers.IO) {
                    opciones.map { it to try { osm.puntajeNoche(it.puntos) } catch (_: Exception) { -1 } }
                }
                val mejor = puntuadas.maxBy { it.second }
                anotar("Noche: ${puntuadas.size} rutas, puntajes ${puntuadas.map { it.second }}; elegida ${mejor.second}")
                mejor.first
            } else opciones.firstOrNull()
        } else {
            pie = null
        }
        actualizarNav(aqui, destinoNombre)
    }

    private fun avanzarNav(p: Punto) {
        val t = tramos.getOrNull(tramoIdx) ?: return
        val meta = t.hastaPunto ?: return
        val r = pie
        if (t.modo == Modo.CAMINAR && r != null) {
            while (pasoIdx < r.pasos.size - 1 && Geo.distancia(p, r.pasos[pasoIdx].punto) < LLEGADA_GIRO_M) pasoIdx++
            if (Geo.distancia(p, meta) < LLEGADA_GIRO_M) { tramoIdx++; lanzar { comenzarTramo() }; return }
            if (Geo.distanciaALinea(p, r.puntos) > DESVIO_M) {
                if (++fueraDeRuta >= 2) {
                    fueraDeRuta = 0
                    _estado.update { it.copy(alerta = alerta(TipoAlerta.DESVIO, "Te desviaste. Recalculando…")) }
                    lanzar { comenzarTramo() }
                    return
                }
            } else fueraDeRuta = 0
        } else {
            val d = Geo.distancia(p, meta)
            val limite = if (t.modo == Modo.CAMINAR || t.modo == Modo.TAXI) LLEGADA_GIRO_M else 60.0
            if (d < limite) { tramoIdx++; lanzar { comenzarTramo() }; return }
            if (t.modo != Modo.CAMINAR && t.modo != Modo.TAXI) {
                if (d < 700 && avisoBajarse == 0) { avisoBajarse = 1; _estado.update { it.copy(alerta = alerta(TipoAlerta.INFO, "Prepárate: bajas en ${t.hasta}")) } }
            }
        }
        actualizarNav(p, _estado.value.hotelElegido?.nombre ?: "Destino")
    }

    private fun actualizarNav(p: Punto, destino: String) {
        val t = tramos.getOrNull(tramoIdx) ?: return
        val meta = t.hastaPunto ?: return
        val r = pie
        val restantesTramos = tramos.drop(tramoIdx + 1).sumOf { it.minutos }
        val nav = if (t.modo == Modo.CAMINAR && r != null && r.pasos.size > 1) {
            val paso = r.pasos[pasoIdx.coerceAtMost(r.pasos.size - 1)]
            val alGiro = Geo.distancia(p, paso.punto).roundToInt()
            val resto = alGiro + r.pasos.drop(pasoIdx).sumOf { it.distanciaM }
            NavActual(
                destino = destino, giro = paso.giro, calle = paso.calle, metrosAlGiro = alGiro,
                flechaGrados = flecha(p, paso.punto), metrosRestantes = resto,
                minutosRestantes = (resto / 80.0).roundToInt() + restantesTramos,
                indicacion = siguienteTransporte(),
            )
        } else {
            val d = Geo.distancia(p, meta).roundToInt()
            val ind = when (t.modo) {
                Modo.CAMINAR -> null
                Modo.TAXI -> "Pide taxi/app a $destino (≈ $${t.costo.roundToInt()})"
                else -> "${t.linea}: de ${t.desde} a ${t.hasta} (${t.paradas} paradas)"
            }
            NavActual(destino, if (t.modo == Modo.CAMINAR) Giro.RECTO else Giro.SALIDA, t.hasta, d, flecha(p, meta), d,
                (d / velocidadMMin(t.modo)).roundToInt() + restantesTramos, ind)
        }
        _estado.update { it.copy(nav = nav) }
    }

    private fun siguienteTransporte(): String? = tramos.getOrNull(tramoIdx + 1)?.takeIf { it.modo != Modo.CAMINAR }?.let {
        if (it.modo == Modo.TAXI) "Después: taxi" else "Después: ${it.linea} en ${it.desde}"
    }

    private fun flecha(p: Punto, objetivo: Punto): Int {
        val r = Geo.rumbo(p, objetivo)
        return rumbo?.let { Geo.diferenciaAngulo(r, it) } ?: r.roundToInt()
    }

    private fun velocidadMMin(m: Modo) = when (m) {
        Modo.CAMINAR -> 80.0; Modo.TAXI -> 350.0; else -> Transporte.velocidad(m) * 1000 / 60
    }

    // ================= Simulación (para demostrar en emuladores) =================

    /** Mueve tu ubicación por la ruta, más rápido que caminar, para probar sin salir. */
    fun simular(activar: Boolean) = lanzar {
        if (!activar) { detenerSimulacion(); return@lanzar }
        if (_estado.value.nav == null) { _estado.update { it.copy(mensaje = "Primero inicia una navegación") }; return@lanzar }
        _estado.update { it.copy(simulando = true) }
        jobSimulacion = scope.launch(hilo) {
            while (isActive && tramos.getOrNull(tramoIdx) != null) {
                val aqui = _estado.value.ubicacion ?: break
                val t = tramos[tramoIdx]
                val puntos = pie?.puntos
                val objetivo = puntos?.drop(indiceMasCercano(aqui) + 1)?.firstOrNull { Geo.distancia(it, aqui) > 3 }
                    ?: t.hastaPunto ?: break
                val paso = if (t.modo == Modo.CAMINAR) 12.0 else 90.0 // m por tick (≈ 8x caminando)
                val d = Geo.distancia(aqui, objetivo)
                val siguiente = if (d <= paso) objetivo else Punto(
                    aqui.lat + (objetivo.lat - aqui.lat) * paso / d,
                    aqui.lon + (objetivo.lon - aqui.lon) * paso / d,
                )
                nuevaUbicacion(siguiente, simulada = true)
                delay(1_000)
            }
            _estado.update { it.copy(simulando = false) }
        }
    }

    private fun indiceMasCercano(p: Punto): Int = pie?.puntos?.withIndex()?.minByOrNull { Geo.distancia(it.value, p) }?.index ?: 0

    private fun detenerSimulacion() {
        jobSimulacion?.cancel(); jobSimulacion = null
        _estado.update { it.copy(simulando = false) }
    }

    // ================= Seguridad =================

    fun panico() = lanzar {
        val p = _estado.value.ubicacion
        val n = withContext(Dispatchers.IO) { seguridad.enviarATodos(seguridad.mensajePanico(p)) }
        val texto = when {
            seguridad.contactos.isEmpty() -> "Sin contactos de confianza. Llama al 911."
            n == 0 -> "No se pudo enviar SMS (permiso). Llama al 911."
            else -> "Alerta enviada a $n contacto(s). Compartiendo ubicación."
        }
        _estado.update { it.copy(alerta = alerta(TipoAlerta.PANICO_ENVIADO, texto)) }
        anotar("PÁNICO: $texto")
        if (n > 0) compartir(true)
    }

    fun estoyBien() = lanzar {
        jobRespuesta?.cancel()
        anclaQuieto = _estado.value.ubicacion; tiempoAncla = System.currentTimeMillis()
        _estado.update { it.copy(alerta = null) }
    }

    fun cerrarAlerta() = lanzar { _estado.update { it.copy(alerta = null) } }

    fun compartir(activar: Boolean) = lanzar {
        jobCompartir?.cancel()
        _estado.update { it.copy(compartiendo = activar) }
        if (!activar) return@lanzar
        ServicioViaje.iniciar(context)
        jobCompartir = scope.launch(hilo) {
            while (isActive) {
                val n = withContext(Dispatchers.IO) { seguridad.enviarATodos(seguridad.mensajeEnVivo(_estado.value.ubicacion)) }
                anotar("Ubicación en vivo enviada a $n contacto(s)")
                delay(INTERVALO_EN_VIVO_MS)
            }
        }
    }

    fun reportarZona() = lanzar {
        val p = _estado.value.ubicacion ?: return@lanzar
        riesgo.reportar(p)
        _estado.update { it.copy(mensaje = "Zona marcada como insegura. Te avisaré si vuelves a pasar.") }
    }

    private fun vigilarQuieto(p: Punto) {
        val e = _estado.value
        val ancla = anclaQuieto
        if (ancla == null || Geo.distancia(ancla, p) > RADIO_QUIETO_M) {
            anclaQuieto = p; tiempoAncla = System.currentTimeMillis(); return
        }
        if (!seguridad.detectarQuieto || (e.nav == null && !e.compartiendo) || e.alerta?.tipo == TipoAlerta.QUIETO) return
        if (e.hotelElegido?.let { Geo.distancia(it.punto, p) < 150 } == true) return
        val minutos = ((System.currentTimeMillis() - tiempoAncla) / 60_000).toInt()
        if (minutos < seguridad.minutosQuieto) return
        _estado.update { it.copy(alerta = alerta(TipoAlerta.QUIETO, "Llevas $minutos min sin moverte. ¿Estás bien?", pide = true)) }
        jobRespuesta?.cancel()
        jobRespuesta = scope.launch(hilo) {
            delay(ESPERA_RESPUESTA_MS)
            if (_estado.value.alerta?.tipo == TipoAlerta.QUIETO) {
                val n = withContext(Dispatchers.IO) { seguridad.enviarATodos(seguridad.mensajeQuieto(_estado.value.ubicacion, minutos)) }
                _estado.update { it.copy(alerta = alerta(TipoAlerta.PANICO_ENVIADO, "No respondiste: aviso enviado a $n contacto(s)")) }
                if (n > 0) compartir(true)
            }
        }
    }

    private fun vigilarRiesgo(p: Punto) {
        if (!seguridad.avisosRiesgo) return
        val ultimo = ultimoRiesgoPos
        if (ultimo != null && Geo.distancia(ultimo, p) < 150) return
        ultimoRiesgoPos = p
        val ciudad = _estado.value.ciudad ?: return
        scope.launch(Dispatchers.IO) {
            val ev = riesgo.evaluar(ciudad, p)
            launch(hilo) {
                val antes = _estado.value.riesgo?.nivel
                _estado.update { it.copy(riesgo = ev) }
                val sube = ev.nivel == NivelRiesgo.ALTO && antes != NivelRiesgo.ALTO ||
                    ev.nivel == NivelRiesgo.MEDIO && antes in setOf(NivelRiesgo.BAJO, NivelRiesgo.DESCONOCIDO, null) && _estado.value.nav != null
                if (sube && ev.nivel != NivelRiesgo.DESCONOCIDO) {
                    _estado.update { it.copy(alerta = alerta(TipoAlerta.RIESGO, "Zona de riesgo ${ev.nivel.name.lowercase()}: ${ev.motivo}")) }
                }
            }
        }
    }

    // ================= Reloj / caché =================

    private fun paraReloj(e: EstadoApp) = EstadoReloj(
        ciudad = e.ciudad?.nombre.orEmpty(),
        buscando = e.buscando || e.calculandoRutas,
        hoteles = e.hoteles.take(MAX_HOTELES_RELOJ).map { it.copy(telefono = null, web = null, direccion = null) },
        orden = e.orden,
        hotelElegido = e.hotelElegido?.id,
        rutas = e.rutas,
        nav = e.nav,
        alerta = e.alerta,
        soloVibracion = e.soloVibracion,
        compartiendo = e.compartiendo,
        mensaje = e.mensaje,
    )

    private fun ordenarLista(l: List<Hotel>, o: Orden) = when (o) {
        Orden.DISTANCIA -> l.sortedBy { it.distanciaM }
        Orden.PRECIO -> l.sortedWith(compareBy({ it.precioEstimado }, { it.distanciaM }))
        Orden.VALORACION -> l.sortedWith(compareByDescending<Hotel> { it.estrellas }.thenBy { it.distanciaM })
    }

    private fun guardarCache() {
        val e = _estado.value
        try {
            archivoCache.writeText(JSONObject().apply {
                put("ciudad", e.ciudad?.id)
                put("hoteles", JSONArray(e.hoteles.map { it.aJson() }))
                put("rutas", JSONArray(e.rutas.map { it.aJson() }))
                put("elegido", e.hotelElegido?.id)
            }.toString())
        } catch (_: Exception) { }
    }

    private fun cargarCache() {
        try {
            if (!archivoCache.exists()) return
            val j = JSONObject(archivoCache.readText())
            val hoteles = j.getJSONArray("hoteles").objetos().map { it.aHotel() }
            _estado.update {
                it.copy(
                    ciudad = Ciudades.porId(j.optString("ciudad")),
                    hoteles = hoteles,
                    rutas = j.getJSONArray("rutas").objetos().map { r -> r.aRuta() },
                    hotelElegido = hoteles.firstOrNull { h -> h.id == j.optString("elegido") },
                    mensaje = if (hoteles.isEmpty()) null else "Mostrando la última búsqueda (guardada sin conexión)",
                )
            }
        } catch (_: Exception) { }
    }

    private var siguienteAlerta = System.currentTimeMillis()
    private fun alerta(tipo: TipoAlerta, texto: String, pide: Boolean = false) = Alerta(siguienteAlerta++, tipo, texto, pide)

    fun anotar(texto: String) {
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _estado.update { it.copy(bitacora = (listOf("$hora  $texto") + it.bitacora).take(40)) }
    }

    companion object {
        private const val TAG = "SerenoMotor"
        private const val LLEGADA_GIRO_M = 18.0
        private const val DESVIO_M = 45.0
        private const val RADIO_QUIETO_M = 40.0
        private const val ESPERA_RESPUESTA_MS = 120_000L
        private const val INTERVALO_EN_VIVO_MS = 5 * 60_000L
        private const val MAX_HOTELES_RELOJ = 8

        @Volatile private var instancia: Motor? = null

        fun crear(context: Context, scope: CoroutineScope): Motor = synchronized(this) {
            instancia ?: Motor(context.applicationContext, scope).also { instancia = it; it.iniciar() }
        }

        fun get(): Motor = instancia ?: error("Motor no iniciado")
    }
}
