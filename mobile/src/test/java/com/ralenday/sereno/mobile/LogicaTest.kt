package com.ralenday.sereno.mobile

import com.ralenday.sereno.core.Giro
import com.ralenday.sereno.core.Modo
import com.ralenday.sereno.core.Orden
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.mobile.datos.Ciudades
import com.ralenday.sereno.mobile.datos.Linea
import com.ralenday.sereno.mobile.datos.Parada
import com.ralenday.sereno.mobile.datos.Ruteo
import com.ralenday.sereno.mobile.datos.Transporte
import com.ralenday.sereno.mobile.logica.Interprete
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicaTest {

    @Test fun fraseDelEjemplo() {
        val p = Interprete.interpretar("Necesito hotel barato cerca del centro y transporte público")
        assertEquals(setOf("hotel"), p.tipos)
        assertTrue(p.barato)
        assertTrue(p.cercaDelCentro)
        assertEquals(Orden.PRECIO, p.orden)
        assertNull(p.cercaDe)
    }

    @Test fun presupuestoYLugar() {
        val p = Interprete.interpretar("Hostal de menos de 600 cerca del Zócalo")
        assertEquals(setOf("hostel"), p.tipos)
        assertEquals(600, p.presupuestoMax)
        assertEquals("zocalo", p.cercaDe)
    }

    @Test fun mejorValorado() {
        assertEquals(Orden.VALORACION, Interprete.interpretar("Hotel mejor valorado").orden)
    }

    @Test fun detectaCiudades() {
        assertEquals("slrc", Ciudades.detectar(Punto(32.46, -114.77))?.id)
        assertEquals("mxl", Ciudades.detectar(Punto(32.62, -115.45))?.id)
        assertEquals("cdmx", Ciudades.detectar(Punto(19.40, -99.15))?.id)
        assertNull(Ciudades.detectar(Punto(25.67, -100.31))) // Monterrey: aún no
    }

    @Test fun planeaMetroConTransbordoYTarifaUnica() {
        val p = { n: String, lat: Double, lon: Double -> Parada(n.hashCode().toLong(), n, Punto(lat, lon)) }
        // Dos líneas en "L" que se cruzan en "Cruce"
        val l1 = Linea(1, "1", "Línea 1", Modo.METRO, "Metro", listOf(p("A", 19.40, -99.20), p("B", 19.40, -99.18), p("Cruce", 19.40, -99.16)))
        val l2 = Linea(2, "2", "Línea 2", Modo.METRO, "Metro", listOf(p("Cruce2", 19.4005, -99.16), p("C", 19.42, -99.16), p("D", 19.44, -99.16)))
        val rutas = Transporte(Ciudades.CDMX).planear(Punto(19.401, -99.201), Punto(19.441, -99.161), listOf(l1, l2))
        val metro = rutas.first { r -> r.tramos.any { it.modo == Modo.METRO } }
        assertEquals(1, metro.transbordos)
        assertEquals(5.0, metro.costo, 0.001) // el transbordo en Metro no se cobra
        assertTrue(rutas.any { it.tramos.singleOrNull()?.modo == Modo.TAXI })
    }

    @Test fun sinLineasQuedaTaxiYCaminar() {
        val rutas = Transporte(Ciudades.SLRC).planear(Punto(32.484, -114.779), Punto(32.470, -114.770), emptyList())
        assertEquals(listOf(Modo.CAMINAR, Modo.TAXI), rutas.map { it.tramos.first().modo })
    }

    @Test fun parseaRespuestaOsrm() {
        val json = JSONObject("""
            {"code":"Ok","routes":[{"distance":250.4,"duration":180.0,"geometry":"_p~iF~ps|U_ulLnnqC",
             "legs":[{"steps":[
               {"name":"Madero","distance":120,"maneuver":{"type":"depart","location":[-99.13,19.43]}},
               {"name":"","distance":130,"maneuver":{"type":"turn","modifier":"left","location":[-99.131,19.431]}},
               {"name":"Isabel la Católica","distance":0,"maneuver":{"type":"arrive","location":[-99.132,19.432]}}]}]}]}
        """.trimIndent())
        val r = Ruteo.parsear(json).single()
        assertEquals(250, r.metros)
        assertEquals(listOf(Giro.SALIDA, Giro.IZQ, Giro.LLEGADA), r.pasos.map { it.giro })
        assertEquals("calle sin nombre", r.pasos[1].calle)
        assertEquals(19.431, r.pasos[1].punto.lat, 1e-9)
    }
}
