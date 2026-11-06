package com.ralenday.sereno.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val RADIO_TIERRA_M = 6_371_000.0

    /** Distancia en metros entre dos puntos (fórmula de haversine). */
    fun distancia(a: Punto, b: Punto): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * RADIO_TIERRA_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Rumbo de a hacia b en grados (0 = norte, 90 = este). */
    fun rumbo(a: Punto, b: Punto): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Diferencia de ángulos normalizada a -180..180. */
    fun diferenciaAngulo(destino: Double, actual: Double): Int {
        var d = (destino - actual) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d.roundToInt()
    }

    /** Distancia mínima (m) de un punto a una polilínea. Aproximación plana, válida a escala de ciudad. */
    fun distanciaALinea(p: Punto, linea: List<Punto>): Double {
        if (linea.isEmpty()) return Double.MAX_VALUE
        if (linea.size == 1) return distancia(p, linea[0])
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_540.0
        var mejor = Double.MAX_VALUE
        for (i in 0 until linea.size - 1) {
            val ax = (linea[i].lon - p.lon) * kx; val ay = (linea[i].lat - p.lat) * ky
            val bx = (linea[i + 1].lon - p.lon) * kx; val by = (linea[i + 1].lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val largo2 = dx * dx + dy * dy
            val t = if (largo2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / largo2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            mejor = minOf(mejor, sqrt(cx * cx + cy * cy))
        }
        return mejor
    }

    /** Decodifica una polilínea codificada de Google/OSRM (precisión 5). */
    fun decodificarPolilinea(codificada: String): List<Punto> {
        val puntos = ArrayList<Punto>()
        var i = 0; var lat = 0; var lon = 0
        while (i < codificada.length) {
            for (eje in 0..1) {
                var resultado = 0; var desplazamiento = 0; var b: Int
                do {
                    b = codificada[i++].code - 63
                    resultado = resultado or ((b and 0x1f) shl desplazamiento)
                    desplazamiento += 5
                } while (b >= 0x20)
                val delta = if (resultado and 1 != 0) (resultado shr 1).inv() else resultado shr 1
                if (eje == 0) lat += delta else lon += delta
            }
            puntos.add(Punto(lat / 1e5, lon / 1e5))
        }
        return puntos
    }

    fun textoDistancia(m: Int): String = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)
}
