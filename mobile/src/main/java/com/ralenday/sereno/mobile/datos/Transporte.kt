package com.ralenday.sereno.mobile.datos

import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.core.Ruta
import com.ralenday.sereno.core.Tramo
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Planificador sencillo de transporte público a partir de las líneas de OSM.
 * No hay horarios en tiempo real (no existen como dato abierto en estas ciudades): los tiempos
 * se estiman con velocidades promedio y una espera típica por modo.
 *
 * Busca viajes directos y con un transbordo. Es puro Kotlin para poder probarlo en JVM.
 */
class Transporte(private val ciudad: Ciudad) {

    private data class Cand(val linea: Linea, val i: Int, val metrosCaminar: Double)

    fun planear(origen: Punto, destino: Punto, lineas: List<Linea>): List<Ruta> {
        val rutas = mutableListOf<Ruta>()
        val directo = Geo.distancia(origen, destino)

        // 1) Caminar todo (si es razonable)
        if (directo <= MAX_CAMINAR_TODO) {
            val min = minCaminar(directo)
            rutas += Ruta("Caminando", listOf(tramoCaminar("Tu ubicación", "Destino", origen, destino)), min, 0.0, 0)
        }

        // 2) Transporte
        val cercaOrigen = candidatos(origen, lineas)
        val cercaDestino = candidatos(destino, lineas)
        val porLinea = cercaDestino.groupBy { it.linea.id }

        // Directos
        for (a in cercaOrigen) {
            val b = porLinea[a.linea.id]?.filter { it.i > a.i }?.minByOrNull { it.metrosCaminar } ?: continue
            rutas += armar(origen, destino, listOf(Triple(a.linea, a.i, b.i)))
        }

        // Un transbordo (paradas a <= 250 m entre líneas distintas)
        for (a in cercaOrigen) {
            for (b in cercaDestino) {
                if (a.linea.id == b.linea.id || a.linea.ref == b.linea.ref && a.linea.modo == b.linea.modo) continue
                var mejor: Triple<Int, Int, Double>? = null
                for (x in a.i + 1 until a.linea.paradas.size) {
                    for (y in 0 until b.i) {
                        val d = Geo.distancia(a.linea.paradas[x].punto, b.linea.paradas[y].punto)
                        if (d <= MAX_TRANSBORDO && (mejor == null || d < mejor.third)) mejor = Triple(x, y, d)
                    }
                }
                val m = mejor ?: continue
                rutas += armar(origen, destino, listOf(Triple(a.linea, a.i, m.first), Triple(b.linea, m.second, b.i)))
            }
        }

        // 3) Taxi / app (siempre como respaldo)
        val metrosAuto = directo * 1.35
        val costoTaxi = Ciudades.costoTaxi(ciudad, metrosAuto, origen, destino).roundToInt().toDouble()
        val notaTaxi = if (ciudad.taxiPorZona) "según zona, desde $${ciudad.taxiBanderazo.roundToInt()}" else ""
        rutas += Ruta(
            "Taxi o app",
            listOf(Tramo(Modo.TAXI, notaTaxi, "Tu ubicación", "Destino", ceil(metrosAuto / 1000 / VEL_TAXI_KMH * 60).toInt() + 5,
                costoTaxi, desdePunto = origen, hastaPunto = destino, trazo = listOf(origen, destino))),
            ceil(metrosAuto / 1000 / VEL_TAXI_KMH * 60).toInt() + 5,
            costoTaxi, 0,
        )

        // Quedarse con la mejor opción por combinación de líneas, ordenadas por tiempo
        return rutas.groupBy { it.titulo }.map { (_, rs) -> rs.minBy { it.minutos } }
            .sortedWith(compareBy<Ruta> { it.tramos.first().modo == Modo.TAXI }.thenBy { it.minutos })
            .take(MAX_RUTAS)
    }

    private fun candidatos(p: Punto, lineas: List<Linea>): List<Cand> = lineas.mapNotNull { l ->
        l.paradas.withIndex()
            .map { (i, s) -> Cand(l, i, Geo.distancia(p, s.punto)) }
            .filter { it.metrosCaminar <= MAX_CAMINAR_A_PARADA }
            .minByOrNull { it.metrosCaminar }
    }

    private fun armar(origen: Punto, destino: Punto, viajes: List<Triple<Linea, Int, Int>>): Ruta {
        val tramos = mutableListOf<Tramo>()
        var ultimoPunto = origen
        var ultimoNombre = "Tu ubicación"
        var modoAnterior: Modo? = null
        var redAnterior = ""
        for ((l, i, j) in viajes) {
            val sube = l.paradas[i]
            val baja = l.paradas[j]
            tramos += tramoCaminar(ultimoNombre, sube.nombre, ultimoPunto, sube.punto)
            var metros = 0.0
            for (k in i until j) metros += Geo.distancia(l.paradas[k].punto, l.paradas[k + 1].punto)
            val min = ceil(metros / 1000 / velocidad(l.modo) * 60).toInt() + espera(l.modo)
            // En el Metro de CDMX el transbordo entre líneas no se vuelve a pagar
            val gratis = modoAnterior == l.modo && redAnterior == l.red && l.modo in setOf(Modo.METRO, Modo.METROBUS)
            val costo = if (gratis) 0.0 else ciudad.tarifas[l.modo] ?: ciudad.tarifas[Modo.BUS] ?: 0.0
            val etiqueta = listOf(l.modo.etiqueta, l.ref.ifBlank { null }).filterNotNull().joinToString(" ")
            val puntosTramo = Geo.simplificar(l.paradas.subList(i, j + 1).map { it.punto }, 15.0)
            tramos += Tramo(l.modo, etiqueta, sube.nombre, baja.nombre, min, costo, j - i, sube.punto, baja.punto, puntosTramo)
            ultimoPunto = baja.punto
            ultimoNombre = baja.nombre
            modoAnterior = l.modo
            redAnterior = l.red
        }
        tramos += tramoCaminar(ultimoNombre, "Destino", ultimoPunto, destino)
        val titulo = tramos.filter { it.modo != Modo.CAMINAR }.joinToString(" → ") { it.linea }
        return Ruta(
            titulo = titulo,
            tramos = tramos.filter { it.modo != Modo.CAMINAR || it.minutos > 0 },
            minutos = tramos.sumOf { it.minutos } + (viajes.size - 1) * PENALIZACION_TRANSBORDO_MIN,
            costo = tramos.sumOf { it.costo },
            transbordos = viajes.size - 1,
        )
    }

    private fun tramoCaminar(desde: String, hasta: String, a: Punto, b: Punto) =
        Tramo(Modo.CAMINAR, "", desde, hasta, minCaminar(Geo.distancia(a, b)), 0.0, desdePunto = a, hastaPunto = b, trazo = listOf(a, b))

    companion object {
        const val MAX_CAMINAR_A_PARADA = 900.0
        const val MAX_TRANSBORDO = 250.0
        const val MAX_CAMINAR_TODO = 3_000.0
        const val MAX_RUTAS = 4
        const val PENALIZACION_TRANSBORDO_MIN = 4
        private const val FACTOR_CALLES = 1.3 // la calle real es más larga que la línea recta
        private const val VEL_CAMINAR_M_MIN = 80.0
        private const val VEL_TAXI_KMH = 22.0

        fun minCaminar(metrosLineaRecta: Double) = ceil(metrosLineaRecta * FACTOR_CALLES / VEL_CAMINAR_M_MIN).toInt()

        /** km/h promedio incluyendo paradas (estimado). */
        fun velocidad(m: Modo) = when (m) {
            Modo.METRO -> 32.0; Modo.TREN -> 45.0; Modo.TREN_LIGERO -> 24.0; Modo.METROBUS -> 19.0
            Modo.CABLEBUS -> 18.0; Modo.TROLEBUS -> 15.0; else -> 14.0
        }

        /** Espera típica en minutos (sin datos en tiempo real). */
        fun espera(m: Modo) = when (m) {
            Modo.METRO -> 4; Modo.METROBUS -> 5; Modo.CABLEBUS -> 2; Modo.TREN -> 8; Modo.TREN_LIGERO -> 6; else -> 12
        }
    }
}
