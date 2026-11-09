package com.ralenday.sereno.mobile.datos

import android.util.Log
import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.objetos
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

enum class NivelRiesgo { BAJO, MEDIO, ALTO, DESCONOCIDO }

data class EvaluacionRiesgo(val nivel: NivelRiesgo, val motivo: String)

/**
 * Riesgo de una zona con datos abiertos.
 *
 * - CDMX: carpetas de investigación de la FGJ (datos.cdmx.gob.mx, API CKAN `datastore_search`),
 *   filtrando robos a transeúnte por alcaldía. Las coordenadas del dataset están desplazadas por
 *   privacidad, por eso se cuenta en un radio de 300 m y no por calle exacta.
 * - SLRC: no hay delitos geolocalizados abiertos (solo cifras municipales del SESNSP),
 *   así que se usan los reportes que el propio usuario marca y la hora.
 *
 * Siempre es orientativo: se muestra como "aviso", nunca como certeza.
 */
class Riesgo(private val osm: Osm, private val cacheDir: File) {

    private val puntosPorAlcaldia = mutableMapOf<String, List<Pair<Punto, Int>>>()
    private val alcaldiaPorCelda = mutableMapOf<String, String?>()
    private val reportesArchivo = File(cacheDir, "reportes_propios.json")

    fun evaluar(ciudad: Ciudad, p: Punto, ahora: Calendar = Calendar.getInstance()): EvaluacionRiesgo {
        val hora = ahora.get(Calendar.HOUR_OF_DAY)
        val noche = hora >= 20 || hora < 6
        reportes().firstOrNull { Geo.distancia(it, p) < 200 }?.let {
            return EvaluacionRiesgo(NivelRiesgo.ALTO, "Marcaste esta zona como insegura")
        }
        return when (ciudad.fuenteRiesgo) {
            FuenteRiesgo.CDMX_FGJ -> evaluarCdmx(p, noche)
            FuenteRiesgo.SOLO_LOCAL -> EvaluacionRiesgo(
                if (noche) NivelRiesgo.MEDIO else NivelRiesgo.DESCONOCIDO,
                if (noche) "De noche: prefiere calles principales" else "Sin datos abiertos de delitos en esta ciudad",
            )
        }
    }

    private fun evaluarCdmx(p: Punto, noche: Boolean): EvaluacionRiesgo {
        val alcaldia = alcaldiaDe(p) ?: return EvaluacionRiesgo(NivelRiesgo.DESCONOCIDO, "No se pudo ubicar la alcaldía")
        val puntos = try { robosEn(alcaldia) } catch (e: Exception) {
            Log.w(TAG, "Sin datos FGJ", e)
            return EvaluacionRiesgo(NivelRiesgo.DESCONOCIDO, "Datos de la FGJ no disponibles")
        }
        val cerca = puntos.filter { Geo.distancia(it.first, p) <= RADIO_M }
        val deNoche = cerca.count { it.second >= 20 || it.second < 6 }
        // Umbrales provisionales (calibrar con más análisis, ver CLAUDE.md)
        val peso = cerca.size + if (noche) deNoche else 0
        val nivel = when {
            peso >= 15 -> NivelRiesgo.ALTO
            peso >= 6 -> NivelRiesgo.MEDIO
            else -> NivelRiesgo.BAJO
        }
        return EvaluacionRiesgo(nivel, "${cerca.size} robos a transeúnte en 300 m ($ANIO_DATOS, FGJ)")
    }

    private fun alcaldiaDe(p: Punto): String? {
        val celda = "%.2f,%.2f".format(java.util.Locale.US, p.lat, p.lon)
        return alcaldiaPorCelda.getOrPut(celda) { try { osm.alcaldia(p) } catch (_: Exception) { null } }
    }

    /** Robos a transeúnte de una alcaldía: (punto, hora). Se guarda en disco 30 días. */
    private fun robosEn(alcaldia: String): List<Pair<Punto, Int>> = puntosPorAlcaldia.getOrPut(alcaldia) {
        val archivo = File(cacheDir, "fgj_${alcaldia.hashCode()}.json")
        val texto = if (archivo.exists() && System.currentTimeMillis() - archivo.lastModified() < TREINTA_DIAS) {
            archivo.readText()
        } else {
            val url = "https://datos.cdmx.gob.mx/api/3/action/datastore_search?resource_id=$RECURSO_FGJ" +
                "&q=TRANSEUNTE&limit=5000&fields=latitud,longitud,hora_hecho" +
                "&filters=" + Http.cod(JSONObject().put("alcaldia_catalogo", alcaldia).toString())
            Http.get(url).also { archivo.writeText(it) }
        }
        JSONObject(texto).getJSONObject("result").getJSONArray("records").objetos().mapNotNull { r ->
            val lat = r.optString("latitud").toDoubleOrNull() ?: return@mapNotNull null
            val lon = r.optString("longitud").toDoubleOrNull() ?: return@mapNotNull null
            Punto(lat, lon) to (r.optString("hora_hecho").take(2).toIntOrNull() ?: 12)
        }
    }

    // ---------- Reportes propios (zonas que el usuario marca) ----------

    fun reportes(): List<Punto> = try {
        if (!reportesArchivo.exists()) emptyList()
        else JSONArray(reportesArchivo.readText()).objetos().map { Punto(it.getDouble("lat"), it.getDouble("lon")) }
    } catch (_: Exception) { emptyList() }

    fun reportar(p: Punto) {
        val arr = JSONArray(reportes().map { JSONObject().put("lat", it.lat).put("lon", it.lon) })
        arr.put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("t", System.currentTimeMillis()))
        reportesArchivo.writeText(arr.toString())
    }

    companion object {
        private const val TAG = "SerenoRiesgo"
        /** "Carpetas de Investigación (2024)": el año más reciente publicado al 2026-11. */
        const val RECURSO_FGJ = "41e05057-26ad-4670-9346-cade8ddbe3fe"
        const val ANIO_DATOS = 2024
        private const val RADIO_M = 300.0
        private const val TREINTA_DIAS = 30L * 24 * 3600 * 1000
    }
}
