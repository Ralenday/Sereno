package com.ralenday.sereno.mobile.datos

import android.content.res.AssetManager
import android.util.Log
import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Hotel
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.objetos
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

data class Parada(val id: Long, val nombre: String, val punto: Punto)

data class Linea(
    val id: Long,
    val ref: String,
    val nombre: String,
    val modo: Modo,
    val red: String,
    /** En orden de recorrido (las relaciones de OSM son de un solo sentido). */
    val paradas: List<Parada>,
)

/** Datos gratuitos de OpenStreetMap: Overpass (consultas) y Nominatim (búsqueda de lugares). */
class Osm(private val cacheDir: File, private val assets: AssetManager?) {

    private val servidores = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    fun overpass(consulta: String, timeoutMs: Int = 35_000): JSONObject {
        var ultimo: Exception? = null
        for (s in servidores) {
            try {
                return JSONObject(Http.postForm(s, mapOf("data" to consulta), timeoutMs = timeoutMs))
            } catch (e: Exception) {
                Log.w(TAG, "Overpass falló en $s: ${e.message}")
                ultimo = e
            }
        }
        throw ultimo ?: IllegalStateException("Overpass no disponible")
    }

    // ---------------- Alojamiento ----------------

    /**
     * Alojamiento en un radio. Usa la copia local de la ciudad (assets/ o la refrescada en disco),
     * que es instantánea y funciona sin datos; solo consulta Overpass si no hay copia.
     */
    fun hoteles(ciudad: Ciudad, centro: Punto, radioM: Int): List<Hotel> {
        val local = copiaLocal(ciudad, "hoteles")
        val json = local ?: overpass(
            """
            [out:json][timeout:40];
            nwr["tourism"~"^(hotel|motel|hostel|guest_house|apartment)$"](around:$radioM,${centro.lat},${centro.lon});
            out center tags 150;
            """.trimIndent(),
        )
        return parsearHoteles(json, ciudad, centro).filter { it.distanciaM <= radioM }
    }

    fun consultaHoteles(ciudad: Ciudad): String {
        val d = cajaGrados(ciudad)
        val c = ciudad.centro
        return "[out:json][timeout:170];nwr[\"tourism\"~\"^(hotel|motel|hostel|guest_house|apartment)$\"]" +
            "(${c.lat - d},${c.lon - d},${c.lat + d},${c.lon + d});out center tags;"
    }

    /** Lo guardado en disco (más nuevo) o, si no hay, la copia incluida en assets/. */
    private fun copiaLocal(ciudad: Ciudad, tipo: String): JSONObject? {
        val archivo = File(cacheDir, "${tipo}_${ciudad.id}.json")
        if (archivo.exists()) try { return JSONObject(archivo.readText()) } catch (_: Exception) { }
        return try {
            assets?.open("${tipo}_${ciudad.id}.json")?.bufferedReader()?.use { JSONObject(it.readText()) }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parsearHoteles(json: JSONObject, ciudad: Ciudad, centro: Punto): List<Hotel> {
        val elementos = json.getJSONArray("elements").objetos()
        return elementos.mapNotNull { e ->
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val p = if (e.has("lat")) Punto(e.getDouble("lat"), e.getDouble("lon"))
            else e.optJSONObject("center")?.let { Punto(it.getDouble("lat"), it.getDouble("lon")) } ?: return@mapNotNull null
            val tipo = tags.optString("tourism")
            val estrellas = tags.optString("stars").take(1).toIntOrNull() ?: 0
            val nombre = tags.optString("name").ifBlank { NOMBRE_TIPO[tipo] + " sin nombre" }
            val calle = tags.optString("addr:street")
            Hotel(
                id = "${e.getString("type")}/${e.getLong("id")}",
                nombre = nombre,
                tipo = tipo,
                punto = p,
                distanciaM = Geo.distancia(centro, p).roundToInt(),
                precioEstimado = Ciudades.precioEstimado(ciudad, tipo, estrellas),
                estrellas = estrellas,
                telefono = tags.optString("phone").ifBlank { tags.optString("contact:phone") }.ifBlank { null },
                web = tags.optString("website").ifBlank { tags.optString("contact:website") }.ifBlank { null },
                direccion = if (calle.isBlank()) null else "$calle ${tags.optString("addr:housenumber")}".trim(),
            )
        }
    }

    // ---------------- Transporte público ----------------

    /**
     * Líneas "fijas" de la ciudad (Metro, tren, cable, Metrobús/Trolebús; en ciudades chicas también
     * camiones). Overpass puede tardar >1 min o fallar, así que la app trae una copia en assets/
     * (generada con la MISMA consulta, ver CLAUDE.md) y la refresca en segundo plano cada 7 días.
     */
    fun lineasFijas(ciudad: Ciudad): List<Linea> = lineasOsm(ciudad) + rutasLocales(ciudad)

    private fun lineasOsm(ciudad: Ciudad): List<Linea> =
        copiaLocal(ciudad, "lineas")?.let { try { parsearLineas(it) } catch (_: Exception) { null } } ?: emptyList()

    /** Rutas capturadas a mano en assets/rutas_locales_<ciudad>.json (ver CLAUDE.md). */
    private fun rutasLocales(ciudad: Ciudad): List<Linea> = try {
        assets!!.open("rutas_locales_${ciudad.id}.json").bufferedReader().use { parsearRutasLocales(JSONObject(it.readText())) }
    } catch (_: Exception) {
        emptyList()
    }

    internal fun parsearRutasLocales(json: JSONObject): List<Linea> =
        json.getJSONArray("rutas").objetos().filter { it.optBoolean("activa", true) }.mapIndexed { i, r ->
            Linea(
                id = -1L - i,
                ref = r.optString("ref"),
                nombre = r.optString("nombre"),
                modo = Modo.valueOf(r.optString("modo", "BUS")),
                red = "local",
                paradas = r.getJSONArray("paradas").objetos().mapIndexed { j, p ->
                    Parada(-1000L * (i + 1) - j, p.getString("nombre"), Punto(p.getDouble("lat"), p.getDouble("lon")))
                },
            )
        }.filter { it.paradas.size >= 2 }

    /** Descarga copias nuevas (líneas y hoteles) si las guardadas tienen más de 7 días. */
    fun refrescarSiHaceFalta(ciudad: Ciudad) {
        for ((tipo, consulta) in listOf("lineas" to consultaLineas(ciudad), "hoteles" to consultaHoteles(ciudad))) {
            val archivo = File(cacheDir, "${tipo}_${ciudad.id}.json")
            if (archivo.exists() && System.currentTimeMillis() - archivo.lastModified() < SIETE_DIAS) continue
            try {
                val json = overpass(consulta, timeoutMs = 180_000)
                if (json.getJSONArray("elements").length() > 0) archivo.writeText(json.toString())
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo refrescar $tipo de ${ciudad.id}: ${e.message}")
            }
        }
    }

    /** Medio lado de la caja de cada ciudad, igual que los snapshots de assets/. */
    private fun cajaGrados(ciudad: Ciudad) = when (ciudad.id) { "cdmx" -> 0.25; else -> 0.135 }

    fun consultaLineas(ciudad: Ciudad): String {
        val d = cajaGrados(ciudad)
        val c = ciudad.centro
        val caja = "(${c.lat - d},${c.lon - d},${c.lat + d},${c.lon + d})"
        val fijas = "subway|light_rail|tram|monorail|train|aerialway"
        return if (ciudad.id == "cdmx") {
            // Metrobús y Trolebús están como route=bus/trolleybus: se filtran por red
            """
            [out:json][timeout:170];
            (rel[type=route][route~"^($fijas)$"]$caja;
             rel[type=route][route=bus][network~"Metrob",i](19.18,-99.38,19.68,-98.88);
             rel[type=route][route=trolleybus](19.18,-99.38,19.68,-98.88);)->.r;
            .r out body;
            node(r.r); out body;
            """.trimIndent()
        } else {
            "[out:json][timeout:170];rel[type=route][route~\"^($fijas|bus|trolleybus|minibus|share_taxi)$\"]$caja->.r;.r out body;node(r.r); out body;"
        }
    }

    /** Camiones / micros que pasan cerca del origen Y cerca del destino (viaje directo). */
    fun lineasDeCamion(origen: Punto, destino: Punto, radioM: Int = 700): List<Linea> {
        val modos = "^(bus|trolleybus|minibus|share_taxi)$"
        val q = """
            [out:json][timeout:60];
            node(around:$radioM,${origen.lat},${origen.lon})->.a; rel(bn.a)[type=route][route~"$modos"]->.ra;
            node(around:$radioM,${destino.lat},${destino.lon})->.b; rel(bn.b)[type=route][route~"$modos"]->.rb;
            rel.ra.rb->.r;
            .r out body;
            node(r.r); out body;
        """.trimIndent()
        return parsearLineas(overpass(q, timeoutMs = 25_000))
    }

    // ---------------- Nominatim ----------------

    /** Busca un lugar ("Zócalo", "Central de autobuses") dentro de la ciudad. */
    fun buscarLugar(texto: String, ciudad: Ciudad): Pair<String, Punto>? {
        val d = 0.3
        val c = ciudad.centro
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&bounded=1" +
            "&viewbox=${c.lon - d},${c.lat + d},${c.lon + d},${c.lat - d}&q=${Http.cod(texto)}"
        val arr = JSONArray(Http.get(url))
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return r.getString("display_name").substringBefore(",") to Punto(r.getString("lat").toDouble(), r.getString("lon").toDouble())
    }

    /** Alcaldía / municipio de un punto (para pedir datos de delitos por alcaldía). */
    fun alcaldia(p: Punto): String? {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=${p.lat}&lon=${p.lon}"
        val dir = JSONObject(Http.get(url)).optJSONObject("address") ?: return null
        return dir.optString("borough").ifBlank { dir.optString("city_district") }.ifBlank { null }
    }

    // ---------------- Parseo ----------------

    internal fun parsearLineas(json: JSONObject): List<Linea> {
        val elementos = json.getJSONArray("elements").objetos()
        val nodos = elementos.filter { it.getString("type") == "node" }.associateBy { it.getLong("id") }
        return elementos.filter { it.getString("type") == "relation" }.mapNotNull { r ->
            val t = r.optJSONObject("tags") ?: return@mapNotNull null
            val miembros = r.getJSONArray("members").objetos().filter { it.getString("type") == "node" }
            val conRolParada = miembros.filter { it.optString("role").startsWith("stop") }
            val elegidos = conRolParada.ifEmpty { miembros.filter { it.optString("role").let { r -> r.isEmpty() || r.startsWith("platform") } } }
            val paradas = elegidos.mapNotNull { m ->
                val n = nodos[m.getLong("ref")] ?: return@mapNotNull null
                val nombre = n.optJSONObject("tags")?.optString("name").orEmpty().ifBlank { "Parada" }
                Parada(n.getLong("id"), nombre, Punto(n.getDouble("lat"), n.getDouble("lon")))
            }.distinctBy { it.id }
            if (paradas.size < 2) return@mapNotNull null
            val red = t.optString("network")
            Linea(
                id = r.getLong("id"),
                ref = t.optString("ref"),
                nombre = t.optString("name").ifBlank { t.optString("ref") },
                modo = modoDe(t.optString("route"), red),
                red = red,
                paradas = paradas,
            )
        }
    }

    private fun modoDe(route: String, red: String): Modo {
        val r = red.lowercase()
        return when (route) {
            "subway", "monorail" -> Modo.METRO
            "light_rail", "tram" -> Modo.TREN_LIGERO
            "train" -> Modo.TREN
            "aerialway" -> Modo.CABLEBUS
            "trolleybus" -> Modo.TROLEBUS
            "minibus", "share_taxi" -> Modo.MICRO
            else -> when {
                "metrob" in r -> Modo.METROBUS
                "trolebús" in r || "trolebus" in r -> Modo.TROLEBUS
                else -> Modo.BUS
            }
        }
    }

    companion object {
        private const val TAG = "SerenoOsm"
        private const val SIETE_DIAS = 7L * 24 * 3600 * 1000
        val NOMBRE_TIPO = mapOf(
            "hotel" to "Hotel", "motel" to "Motel", "hostel" to "Hostal",
            "guest_house" to "Casa de huéspedes", "apartment" to "Departamento",
        )
    }
}
