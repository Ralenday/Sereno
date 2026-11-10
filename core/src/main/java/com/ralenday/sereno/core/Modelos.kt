package com.ralenday.sereno.core

import org.json.JSONArray
import org.json.JSONObject

/*
 * Todo lo que viaja entre teléfono y reloj. El teléfono hace el trabajo pesado (internet, GPS,
 * cálculos) y le manda al reloj solo un [EstadoReloj] ya resumido; el reloj solo devuelve [Comando]s.
 */

data class Punto(val lat: Double, val lon: Double)

enum class Giro { SALIDA, RECTO, LIGERA_IZQ, IZQ, FUERTE_IZQ, LIGERA_DER, DER, FUERTE_DER, VUELTA_U, ROTONDA, LLEGADA }

enum class Modo(val etiqueta: String) {
    CAMINAR("Caminar"), METRO("Metro"), TREN_LIGERO("Tren ligero"), METROBUS("Metrobús"),
    TROLEBUS("Trolebús"), CABLEBUS("Cablebús"), BUS("Camión"), MICRO("Micro / combi"),
    TREN("Tren"), TAXI("Taxi / app"),
}

enum class Orden { DISTANCIA, PRECIO, VALORACION }

data class Hotel(
    val id: String,
    val nombre: String,
    val tipo: String,
    val punto: Punto,
    val distanciaM: Int,
    val precioEstimado: Int,
    /** Estrellas (de OSM cuando existen; si no, estimadas por cadena/tipo en el teléfono). */
    val estrellas: Int,
    /** Calificación estimada 0–5 estilo reseñas (0 = sin dato). */
    val calificacion: Double = 0.0,
    val telefono: String? = null,
    val web: String? = null,
    val direccion: String? = null,
)

/** Un giro de la navegación a pie. */
data class PasoNav(
    val giro: Giro,
    val calle: String,
    val punto: Punto,
    /** Metros desde este giro hasta el siguiente. */
    val distanciaM: Int,
)

/** Un tramo de un trayecto: caminar, subir al metro, etc. */
data class Tramo(
    val modo: Modo,
    val linea: String,
    val desde: String,
    val hasta: String,
    val minutos: Int,
    val costo: Double,
    val paradas: Int = 0,
    val desdePunto: Punto? = null,
    val hastaPunto: Punto? = null,
    /** Recorrido del tramo para dibujarlo en el mapa (puede venir vacío). */
    val trazo: List<Punto> = emptyList(),
)

data class Ruta(
    val titulo: String,
    val tramos: List<Tramo>,
    val minutos: Int,
    val costo: Double,
    val transbordos: Int,
    val estimada: Boolean = true,
)

data class NavActual(
    val destino: String,
    val giro: Giro,
    val calle: String,
    val metrosAlGiro: Int,
    /** Hacia dónde apuntar la flecha, en grados respecto al rumbo actual (0 = de frente). */
    val flechaGrados: Int,
    val metrosRestantes: Int,
    val minutosRestantes: Int,
    /** Instrucción del tramo de transporte actual, p. ej. "Sube a Metro L2 dir. Cuatro Caminos". */
    val indicacion: String? = null,
    /** Rumbo geográfico absoluto (0 = norte, 90 = este) hacia el siguiente giro, para la brújula local del reloj. */
    val rumboObjetivo: Int = 0,
    /** Coordenada del siguiente giro para destacarlo en el croquis del reloj. */
    val puntoGiro: Punto? = null,
    /** Si el simulador de caminata está activo. */
    val simulando: Boolean = false,
)

enum class TipoAlerta { RIESGO, QUIETO, DESVIO, INFO }

data class Alerta(val id: Long, val tipo: TipoAlerta, val texto: String, val pideRespuesta: Boolean = false)

/** Un trazo del mapa del reloj con el modo (para el color). */
data class TrazoMapa(val modo: Modo, val puntos: List<Punto>)

data class MapaReloj(
    val yo: Punto?,
    val destino: Punto?,
    val destinoNombre: String = "",
    val trazos: List<TrazoMapa> = emptyList(),
    /** Rumbo actual de la brújula del teléfono (0..359°). */
    val rumbo: Int = 0,
)

data class EstadoReloj(
    val ciudad: String = "",
    val buscando: Boolean = false,
    val hoteles: List<Hotel> = emptyList(),
    val orden: Orden = Orden.DISTANCIA,
    val hotelElegido: String? = null,
    val rutas: List<Ruta> = emptyList(),
    val nav: NavActual? = null,
    val alerta: Alerta? = null,
    val soloVibracion: Boolean = false,
    val mensaje: String? = null,
    /** Lo que dibuja el mapa del reloj: tu posición, el destino y el trazo simplificado de la ruta activa. */
    val mapa: MapaReloj? = null,
    val generado: Long = System.currentTimeMillis(),
) {
    fun aBytes(): ByteArray = aJson().toString().toByteArray()

    fun aJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("ciudad", ciudad)
        put("buscando", buscando)
        put("hoteles", JSONArray(hoteles.map { it.aJson() }))
        put("orden", orden.name)
        put("hotelElegido", hotelElegido ?: JSONObject.NULL)
        put("rutas", JSONArray(rutas.map { it.aJson() }))
        put("nav", nav?.aJson() ?: JSONObject.NULL)
        put("alerta", alerta?.aJson() ?: JSONObject.NULL)
        put("soloVibracion", soloVibracion)
        put("mensaje", mensaje ?: JSONObject.NULL)
        put("mapa", mapa?.aJson() ?: JSONObject.NULL)
        put("generado", generado)
    }

    companion object {
        fun deBytes(bytes: ByteArray) = deJson(JSONObject(String(bytes)))

        fun deJson(j: JSONObject) = EstadoReloj(
            ciudad = j.optString("ciudad"),
            buscando = j.optBoolean("buscando"),
            hoteles = j.getJSONArray("hoteles").objetos().map { it.aHotel() },
            orden = Orden.valueOf(j.optString("orden", Orden.DISTANCIA.name)),
            hotelElegido = j.texto("hotelElegido"),
            rutas = j.getJSONArray("rutas").objetos().map { it.aRuta() },
            nav = j.optJSONObject("nav")?.aNav(),
            alerta = j.optJSONObject("alerta")?.aAlerta(),
            soloVibracion = j.optBoolean("soloVibracion"),
            mensaje = j.texto("mensaje"),
            mapa = j.optJSONObject("mapa")?.aMapa(),
            generado = j.optLong("generado"),
        )
    }
}

enum class TipoComando {
    PEDIR_ESTADO, BUSCAR_HOTELES, ORDENAR, ELEGIR_HOTEL, ELEGIR_RUTA, INICIAR_NAV, DETENER_NAV,
    RECALCULAR, SIMULAR_NAV, ESTOY_BIEN, SOLO_VIBRACION,
}

data class Comando(val tipo: TipoComando, val arg: String? = null) {
    fun aBytes(): ByteArray = JSONObject().put("tipo", tipo.name).put("arg", arg ?: JSONObject.NULL).toString().toByteArray()

    companion object {
        fun deBytes(bytes: ByteArray): Comando {
            val j = JSONObject(String(bytes))
            return Comando(TipoComando.valueOf(j.getString("tipo")), j.texto("arg"))
        }
    }
}

// ---------- JSON ----------

fun JSONArray.objetos(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

fun JSONObject.texto(clave: String): String? = if (!has(clave) || isNull(clave)) null else optString(clave)

private fun Punto.aJson() = JSONObject().put("lat", lat).put("lon", lon)
private fun JSONObject.aPunto() = Punto(getDouble("lat"), getDouble("lon"))
private fun JSONObject.punto(clave: String) = optJSONObject(clave)?.aPunto()

fun Hotel.aJson(): JSONObject = JSONObject().apply {
    put("id", id); put("nombre", nombre); put("tipo", tipo); put("p", punto.aJson())
    put("dist", distanciaM); put("precio", precioEstimado); put("estrellas", estrellas)
    put("calif", calificacion)
    put("tel", telefono ?: JSONObject.NULL); put("web", web ?: JSONObject.NULL)
    put("dir", direccion ?: JSONObject.NULL)
}

fun JSONObject.aHotel() = Hotel(
    id = getString("id"), nombre = getString("nombre"), tipo = getString("tipo"),
    punto = getJSONObject("p").aPunto(), distanciaM = getInt("dist"),
    precioEstimado = getInt("precio"), estrellas = optInt("estrellas"),
    calificacion = optDouble("calif", 0.0).takeIf { !it.isNaN() } ?: 0.0,
    telefono = texto("tel"), web = texto("web"), direccion = texto("dir"),
)

private fun Tramo.aJson() = JSONObject().apply {
    put("modo", modo.name); put("linea", linea); put("desde", desde); put("hasta", hasta)
    put("min", minutos); put("costo", costo); put("paradas", paradas)
    put("dp", desdePunto?.aJson() ?: JSONObject.NULL); put("hp", hastaPunto?.aJson() ?: JSONObject.NULL)
    if (trazo.size >= 2) put("tz", Geo.codificarPolilinea(trazo))
}

private fun JSONObject.aTramo() = Tramo(
    modo = Modo.valueOf(getString("modo")), linea = getString("linea"), desde = getString("desde"),
    hasta = getString("hasta"), minutos = getInt("min"), costo = getDouble("costo"),
    paradas = optInt("paradas"), desdePunto = punto("dp"), hastaPunto = punto("hp"),
    trazo = texto("tz")?.let { Geo.decodificarPolilinea(it) } ?: emptyList(),
)

fun Ruta.aJson(): JSONObject = JSONObject().apply {
    put("titulo", titulo); put("tramos", JSONArray(tramos.map { it.aJson() }))
    put("min", minutos); put("costo", costo); put("transbordos", transbordos)
    put("estimada", estimada)
}

fun JSONObject.aRuta() = Ruta(
    titulo = getString("titulo"), tramos = getJSONArray("tramos").objetos().map { it.aTramo() },
    minutos = getInt("min"), costo = getDouble("costo"), transbordos = getInt("transbordos"),
    estimada = optBoolean("estimada", true),
)

private fun NavActual.aJson() = JSONObject().apply {
    put("destino", destino); put("giro", giro.name); put("calle", calle); put("mGiro", metrosAlGiro)
    put("flecha", flechaGrados); put("mRest", metrosRestantes); put("minRest", minutosRestantes)
    put("ind", indicacion ?: JSONObject.NULL)
    put("rumboObj", rumboObjetivo)
    put("pGiro", puntoGiro?.aJson() ?: JSONObject.NULL)
    put("sim", simulando)
}

private fun JSONObject.aNav() = NavActual(
    destino = getString("destino"), giro = Giro.valueOf(getString("giro")), calle = getString("calle"),
    metrosAlGiro = getInt("mGiro"), flechaGrados = getInt("flecha"), metrosRestantes = getInt("mRest"),
    minutosRestantes = getInt("minRest"), indicacion = texto("ind"),
    rumboObjetivo = optInt("rumboObj", 0),
    puntoGiro = punto("pGiro"),
    simulando = optBoolean("sim", false),
)

private fun Alerta.aJson() = JSONObject().put("id", id).put("tipo", tipo.name).put("texto", texto).put("resp", pideRespuesta)
private fun JSONObject.aAlerta() = Alerta(getLong("id"), TipoAlerta.valueOf(getString("tipo")), getString("texto"), optBoolean("resp"))

fun PasoNav.aJson(): JSONObject = JSONObject().put("giro", giro.name).put("calle", calle).put("p", punto.aJson()).put("d", distanciaM)
fun JSONObject.aPaso() = PasoNav(Giro.valueOf(getString("giro")), getString("calle"), getJSONObject("p").aPunto(), getInt("d"))
fun Punto.json(): JSONObject = aJson()
fun JSONObject.comoPunto(): Punto = aPunto()

private fun MapaReloj.aJson() = JSONObject().apply {
    put("yo", yo?.aJson() ?: JSONObject.NULL); put("dest", destino?.aJson() ?: JSONObject.NULL)
    put("destN", destinoNombre)
    put("tr", JSONArray(trazos.map { JSONObject().put("m", it.modo.name).put("p", Geo.codificarPolilinea(it.puntos)) }))
    put("rumbo", rumbo)
}

private fun JSONObject.aMapa() = MapaReloj(
    yo = punto("yo"), destino = punto("dest"), destinoNombre = optString("destN"),
    trazos = optJSONArray("tr")?.objetos()?.map { TrazoMapa(Modo.valueOf(it.getString("m")), Geo.decodificarPolilinea(it.getString("p"))) } ?: emptyList(),
    rumbo = optInt("rumbo", 0),
)

/**
 * Devuelve el estado con la brújula en vivo aplicada: actualiza `mapa.rumbo` y recalcula la flecha de
 * navegación con `rumboObjetivo`, sin esperar a que el teléfono mande el estado completo.
 */
fun EstadoReloj.conRumbo(rumbo: Int?): EstadoReloj {
    if (rumbo == null) return this
    val r = ((rumbo % 360) + 360) % 360
    val nuevoMapa = mapa?.copy(rumbo = r)
    val nuevaNav = nav?.let { n ->
        if (n.giro == Giro.LLEGADA) n
        else n.copy(flechaGrados = Geo.diferenciaAngulo(n.rumboObjetivo.toDouble(), r.toDouble()))
    }
    if (nuevoMapa == mapa && nuevaNav == nav) return this
    return copy(mapa = nuevoMapa, nav = nuevaNav)
}
