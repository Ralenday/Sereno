package com.ralenday.sereno.mobile.datos

import com.ralenday.sereno.core.Geo
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Punto

enum class FuenteRiesgo {
    /** Carpetas de investigación FGJ CDMX geolocalizadas (datos.cdmx.gob.mx). */
    CDMX_FGJ,
    /** No hay datos abiertos geolocalizados: solo iluminación (OSM), hora y reportes propios. */
    SOLO_LOCAL,
}

/**
 * Datos por ciudad. Las tarifas y precios marcados como "estimado" NO vienen de una API (no existe
 * una gratuita para eso); hay que revisarlos periódicamente. Ver CLAUDE.md -> Pendientes.
 */
data class Ciudad(
    val id: String,
    val nombre: String,
    val tamano: String,
    val centro: Punto,
    val radioKm: Double,
    val tarifas: Map<Modo, Double>,
    /** Tarifa preferencial (estudiantes y adultos mayores), si aplica. */
    val tarifaDescuento: Double? = null,
    val tarifasVerificadas: Boolean,
    val taxiBanderazo: Double,
    val taxiPorKm: Double,
    /** En ciudades como SLRC el taxi cobra por zona/cuadro (desde $45) más que por taxímetro/km. */
    val taxiPorZona: Boolean = false,
    /** Precio por noche estimado (MXN) por tipo de alojamiento de OSM. */
    val preciosNoche: Map<String, Int>,
    val fuenteRiesgo: FuenteRiesgo,
    /** Si hay transporte mapeado en OpenStreetMap o en rutas_locales_<ciudad>.json. */
    val transporteEnOsm: String,
)

object Ciudades {
    val SLRC = Ciudad(
        id = "slrc", nombre = "San Luis Río Colorado", tamano = "Chica",
        centro = Punto(32.4840, -114.7790), radioKm = 15.0,
        // Tarifa de combi/pesera: $14 general, $7 descuento estudiante y adulto mayor.
        tarifas = mapOf(Modo.BUS to 14.0, Modo.MICRO to 14.0),
        tarifaDescuento = 7.0,
        tarifasVerificadas = true,
        taxiBanderazo = 45.0, taxiPorKm = 0.0, taxiPorZona = true,
        preciosNoche = mapOf("hotel" to 950, "motel" to 650, "guest_house" to 750, "hostel" to 450, "apartment" to 900),
        fuenteRiesgo = FuenteRiesgo.SOLO_LOCAL,
        transporteEnOsm = "Local: 4 rutas de combi mapeadas en assets/rutas_locales_slrc.json ($14 general / $7 est. y mayores)",
    )

    val CDMX = Ciudad(
        id = "cdmx", nombre = "Ciudad de México", tamano = "Grande",
        centro = Punto(19.4326, -99.1332), radioKm = 40.0,
        // Tarifas oficiales 2026 (Azteca Noticias, jun-2026). Metrobús L4 al aeropuerto: $30.
        tarifas = mapOf(
            Modo.METRO to 5.0, Modo.METROBUS to 6.0, Modo.TROLEBUS to 4.0, Modo.TREN_LIGERO to 3.0,
            Modo.CABLEBUS to 7.0, Modo.BUS to 2.0 /* RTP ordinario */, Modo.MICRO to 7.5, Modo.TREN to 13.0,
        ),
        tarifasVerificadas = true,
        taxiBanderazo = 35.0, taxiPorKm = 9.0, taxiPorZona = false,
        preciosNoche = mapOf("hotel" to 1600, "motel" to 750, "guest_house" to 950, "hostel" to 420, "apartment" to 1300),
        fuenteRiesgo = FuenteRiesgo.CDMX_FGJ,
        transporteEnOsm = "Bueno (Metro, Metrobús, Trolebús, Tren Ligero, Cablebús mapeados)",
    )

    val todas = listOf(SLRC, CDMX)

    /** La ciudad soportada más cercana, o null si estás lejos de las soportadas. */
    fun detectar(p: Punto): Ciudad? = todas
        .map { it to Geo.distancia(p, it.centro) / 1000 }
        .filter { (c, km) -> km <= c.radioKm }
        .minByOrNull { it.second }?.first

    fun porId(id: String?) = todas.firstOrNull { it.id == id }

    /** Precio estimado según tipo y estrellas OSM (si las hay). */
    fun precioEstimado(c: Ciudad, tipo: String, estrellas: Int): Int {
        val base = c.preciosNoche[tipo] ?: c.preciosNoche.getValue("hotel")
        val factor = if (estrellas > 0) 1.0 + 0.35 * (estrellas - 2) else 1.0
        return (base * factor.coerceAtLeast(0.6) / 50).toInt() * 50
    }

    /**
     * Costo estimado de taxi. En SLRC el cobro es por zona (desde $45 MXN hacia arriba según el
     * cuadro/colonia de origen y destino, más que por distancia). En CDMX es banderazo + $/km.
     */
    fun costoTaxi(c: Ciudad, metros: Double, origen: Punto? = null, destino: Punto? = null): Double {
        if (!c.taxiPorZona) return c.taxiBanderazo + c.taxiPorKm * metros / 1000
        val zOrigen = origen?.let { zonaSlrc(c.centro, it) } ?: 0
        val zDestino = destino?.let { zonaSlrc(c.centro, it) } ?: escalonPorMetros(metros)
        val saltoZonas = kotlin.math.abs(zOrigen - zDestino)
        val zonaMax = maxOf(zOrigen, zDestino)
        // Base $45 en primer cuadro; sube por cuadro/zona ($60, $75, $90...)
        return c.taxiBanderazo + 15.0 * maxOf(zonaMax, saltoZonas)
    }

    private fun zonaSlrc(centro: Punto, p: Punto): Int {
        val km = Geo.distancia(centro, p) / 1000.0
        return when {
            km <= 1.8 -> 0 // Zona Centro / Comercial ($45)
            km <= 3.5 -> 1 // Zona intermedia: Col. Sonora, Burócrata, Parque La Tortuga ($60)
            km <= 5.5 -> 2 // Mezquite, Progreso, México, 10 de Abril, IMSS ($75)
            else -> 3      // Parque Industrial, 5 de Mayo Sur, periferia ($90)
        }
    }

    private fun escalonPorMetros(metros: Double): Int = when {
        metros <= 2_200 -> 0
        metros <= 4_200 -> 1
        metros <= 6_500 -> 2
        else -> 3
    }
}
