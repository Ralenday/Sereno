package com.ralenday.sereno.mobile

import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.mobile.datos.Ciudades
import com.ralenday.sereno.mobile.datos.Linea
import com.ralenday.sereno.mobile.datos.Osm
import com.ralenday.sereno.mobile.datos.Transporte
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Verifica las copias de líneas de OSM incluidas en assets/. */
class SnapshotTest {
    private fun lineas(id: String): List<Linea> =
        Osm(File("build/tmp"), null).parsearLineas(JSONObject(File("src/main/assets/lineas_$id.json").readText()))

    @Test fun cdmxTieneMetroYMetrobus() {
        val l = lineas("cdmx")
        assertTrue(l.count { it.modo == Modo.METRO } >= 20)
        assertTrue(l.any { it.modo == Modo.METROBUS })
    }

    @Test fun zocaloAPolancoEnTransporte() {
        val rutas = Transporte(Ciudades.CDMX).planear(Punto(19.4326, -99.1332), Punto(19.4330, -99.1910), lineas("cdmx"))
        println(rutas.joinToString("\n") { "${it.minutos} min $${it.costo} ${it.titulo}" })
        val mejor = rutas.first()
        assertTrue(mejor.tramos.any { it.modo != Modo.CAMINAR && it.modo != Modo.TAXI })
        assertTrue(mejor.minutos in 15..70)
    }

    /** En OSM no hay rutas mexicanas con paradas en SLRC, pero sí en rutas_locales_slrc.json. */
    @Test fun slrcTieneRutasLocalesDeCombi() {
        val osm = Osm(File("build/tmp"), null)
        assertTrue(lineas("slrc").isEmpty())
        val locales = osm.parsearRutasLocales(JSONObject(File("src/main/assets/rutas_locales_slrc.json").readText()))
        assertTrue(locales.size >= 8) // 4 rutas en ambos sentidos
        // De Garita/Centro a IMSS / Hospital (Calle 26) o Hotel Araiza en combi ($14)
        val rutas = Transporte(Ciudades.SLRC).planear(
            Punto(32.4831, -114.7809), // Hotel San Antonio (Centro)
            Punto(32.4222, -114.7433), // IMSS No. 38 (Calle 42)
            locales,
        )
        val enCombi = rutas.first { r -> r.tramos.any { it.modo == Modo.MICRO } }
        org.junit.Assert.assertEquals(14.0, enCombi.costo, 0.001)
    }

    @Test fun plantillaDeRutasLocalesCdmxSeIgnora() {
        val osm = Osm(File("build/tmp"), null)
        assertTrue(osm.parsearRutasLocales(JSONObject(File("src/main/assets/rutas_locales_cdmx.json").readText())).isEmpty())
        val una = JSONObject("""{"rutas":[{"ref":"3","nombre":"Ruta 3","modo":"BUS","paradas":[
            {"nombre":"A","lat":32.48,"lon":-114.78},{"nombre":"B","lat":32.46,"lon":-114.76}]}]}""")
        assertTrue(osm.parsearRutasLocales(una).single().paradas.size == 2)
    }
}
