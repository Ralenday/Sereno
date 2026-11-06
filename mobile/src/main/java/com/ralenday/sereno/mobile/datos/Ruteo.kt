package com.ralenday.sereno.mobile.datos

import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Giro
import com.ralenday.sereno.core.PasoNav
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.objetos
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

data class RutaPie(val metros: Int, val segundos: Int, val puntos: List<Punto>, val pasos: List<PasoNav>)

/**
 * Rutas a pie con giro por giro usando el servidor público de OSRM de FOSSGIS
 * (routing.openstreetmap.de, perfil "foot"). Gratis, sin llave, uso razonable.
 */
object Ruteo {
    private const val SERVIDOR = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"

    fun caminar(origen: Punto, destino: Punto, alternativas: Boolean = false): List<RutaPie> {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", origen.lon, origen.lat, destino.lon, destino.lat)
        val url = "$SERVIDOR$coords?overview=full&geometries=polyline&steps=true&alternatives=$alternativas"
        return parsear(JSONObject(Http.get(url)))
    }

    fun parsear(json: JSONObject): List<RutaPie> {
        if (json.optString("code") != "Ok") error("OSRM: ${json.optString("message", json.optString("code"))}")
        return json.getJSONArray("routes").objetos().map { r ->
            val pasos = r.getJSONArray("legs").objetos().flatMap { it.getJSONArray("steps").objetos() }.map { s ->
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                PasoNav(
                    giro = giroDe(m.optString("type"), m.optString("modifier")),
                    calle = s.optString("name").ifBlank { "calle sin nombre" },
                    punto = Punto(loc.getDouble(1), loc.getDouble(0)),
                    distanciaM = s.optDouble("distance").roundToInt(),
                )
            }
            RutaPie(
                metros = r.getDouble("distance").roundToInt(),
                // OSRM de FOSSGIS calcula ~5 km/h; se respeta
                segundos = r.getDouble("duration").roundToInt(),
                puntos = Geo.decodificarPolilinea(r.getString("geometry")),
                pasos = pasos,
            )
        }
    }

    fun giroDe(tipo: String, modificador: String): Giro = when (tipo) {
        "depart" -> Giro.SALIDA
        "arrive" -> Giro.LLEGADA
        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> Giro.ROTONDA
        else -> when (modificador) {
            "slight left" -> Giro.LIGERA_IZQ
            "left" -> Giro.IZQ
            "sharp left" -> Giro.FUERTE_IZQ
            "slight right" -> Giro.LIGERA_DER
            "right" -> Giro.DER
            "sharp right" -> Giro.FUERTE_DER
            "uturn" -> Giro.VUELTA_U
            else -> Giro.RECTO
        }
    }
}
