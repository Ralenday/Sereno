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

    /** Documenta el hueco de datos: en OSM no hay rutas mexicanas con paradas en SLRC ni Mexicali. */
    @Test fun ciudadesChicasSinRutasEnOsm() {
        assertTrue(lineas("slrc").isEmpty())
        assertTrue(lineas("mxl").isEmpty())
    }

    @Test fun plantillaDeRutasLocalesSeIgnora() {
        val osm = Osm(File("build/tmp"), null)
        for (c in listOf("slrc", "mxl", "cdmx")) {
            assertTrue(osm.parsearRutasLocales(JSONObject(File("src/main/assets/rutas_locales_$c.json").readText())).isEmpty())
        }
        val una = JSONObject("""{"rutas":[{"ref":"3","nombre":"Ruta 3","modo":"BUS","paradas":[
            {"nombre":"A","lat":32.48,"lon":-114.78},{"nombre":"B","lat":32.46,"lon":-114.76}]}]}""")
        assertTrue(osm.parsearRutasLocales(una).single().paradas.size == 2)
    }
}
