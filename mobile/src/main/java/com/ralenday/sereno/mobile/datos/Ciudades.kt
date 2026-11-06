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
    val tarifasVerificadas: Boolean,
    val taxiBanderazo: Double,
    val taxiPorKm: Double,
    /** Precio por noche estimado (MXN) por tipo de alojamiento de OSM. */
    val preciosNoche: Map<String, Int>,
    val fuenteRiesgo: FuenteRiesgo,
    /** Si hay transporte mapeado en OpenStreetMap con paradas (relaciones route=*). */
    val transporteEnOsm: String,
)

object Ciudades {
    val SLRC = Ciudad(
        id = "slrc", nombre = "San Luis Río Colorado", tamano = "Chica",
        centro = Punto(32.4840, -114.7790), radioKm = 15.0,
        // ESTIMADO: no se encontró tarifa oficial 2026 publicada. Verificar con el Ayuntamiento.
        tarifas = mapOf(Modo.BUS to 15.0, Modo.MICRO to 15.0),
        tarifasVerificadas = false,
        taxiBanderazo = 40.0, taxiPorKm = 10.0,
        preciosNoche = mapOf("hotel" to 950, "motel" to 650, "guest_house" to 750, "hostel" to 450, "apartment" to 900),
        fuenteRiesgo = FuenteRiesgo.SOLO_LOCAL,
        transporteEnOsm = "Ninguno: las 3 rutas en OSM son de San Luis AZ (YCAT) y sin paradas",
    )

    val MEXICALI = Ciudad(
        id = "mxl", nombre = "Mexicali", tamano = "Mediana",
        centro = Punto(32.6640, -115.4920), radioKm = 25.0,
        // ESTIMADO: la última tarifa confirmada encontrada fue $15.50 (2018) y hubo ajuste en 2024.
        // Verificar en IMOS Baja California / Periódico Oficial.
        tarifas = mapOf(Modo.BUS to 17.0, Modo.MICRO to 17.0),
        tarifasVerificadas = false,
        taxiBanderazo = 45.0, taxiPorKm = 11.0,
        preciosNoche = mapOf("hotel" to 1300, "motel" to 750, "guest_house" to 850, "hostel" to 450, "apartment" to 1100),
        fuenteRiesgo = FuenteRiesgo.SOLO_LOCAL,
        transporteEnOsm = "Ninguno: las rutas en OSM son del Valle Imperial (EE. UU.) y sin paradas",
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
        taxiBanderazo = 35.0, taxiPorKm = 9.0,
        preciosNoche = mapOf("hotel" to 1600, "motel" to 750, "guest_house" to 950, "hostel" to 420, "apartment" to 1300),
        fuenteRiesgo = FuenteRiesgo.CDMX_FGJ,
        transporteEnOsm = "Bueno (Metro, Metrobús, Trolebús, Tren Ligero, Cablebús mapeados)",
    )

    val todas = listOf(SLRC, MEXICALI, CDMX)

    /** La ciudad soportada más cercana, o null si estás lejos de las tres. */
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

    fun costoTaxi(c: Ciudad, metros: Double) = c.taxiBanderazo + c.taxiPorKm * metros / 1000
}
