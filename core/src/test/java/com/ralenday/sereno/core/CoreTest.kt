package com.ralenday.sereno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CoreTest {
    private val zocalo = Punto(19.4326, -99.1332)
    private val bellasArtes = Punto(19.4352, -99.1412)

    @Test fun distanciaZocaloBellasArtes() {
        val d = Geo.distancia(zocalo, bellasArtes)
        assertTrue("fue $d", d in 850.0..950.0)
    }

    @Test fun rumboEsteYDiferencia() {
        assertEquals(90.0, Geo.rumbo(Punto(0.0, 0.0), Punto(0.0, 1.0)), 0.5)
        assertEquals(-20, Geo.diferenciaAngulo(350.0, 10.0))
        assertEquals(30, Geo.diferenciaAngulo(20.0, 350.0))
    }

    @Test fun polilineaDeEjemploDeGoogle() {
        val p = Geo.decodificarPolilinea("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-5); assertEquals(-120.2, p[0].lon, 1e-5)
        assertEquals(43.252, p[2].lat, 1e-5); assertEquals(-126.453, p[2].lon, 1e-5)
    }

    @Test fun distanciaALinea() {
        val linea = listOf(Punto(0.0, 0.0), Punto(0.0, 0.01))
        assertEquals(111.0, Geo.distanciaALinea(Punto(0.001, 0.005), linea), 2.0)
    }

    @Test fun estadoRelojIdaYVuelta() {
        val e = EstadoReloj(
            ciudad = "CDMX",
            hoteles = listOf(Hotel("node/1", "Hotel Ñandú", "hotel", zocalo, 120, 1600, 3)),
            hotelElegido = "node/1",
            rutas = listOf(Ruta("Metro 2", listOf(Tramo(Modo.METRO, "Metro 2", "Zócalo", "Allende", 6, 5.0, 2, zocalo, bellasArtes)), 12, 5.0, 0)),
            nav = NavActual("Hotel", Giro.IZQ, "Madero", 40, -90, 500, 7, "Metro 2"),
            alerta = Alerta(3, TipoAlerta.QUIETO, "¿Estás bien?", true),
            generado = 42,
        )
        assertEquals(e, EstadoReloj.deBytes(e.aBytes()))
        val c = Comando(TipoComando.ELEGIR_HOTEL, "node/1")
        assertEquals(c, Comando.deBytes(c.aBytes()))
    }

    @Test fun rumboVivoRecalculaFlecha() {
        val nav = NavActual(
            destino = "Hotel", giro = Giro.DER, calle = "Madero", metrosAlGiro = 50, flechaGrados = 0,
            metrosRestantes = 300, minutosRestantes = 4, rumboObjetivo = 90,
        )
        val e = EstadoReloj(nav = nav, mapa = MapaReloj(yo = zocalo, destino = bellasArtes, rumbo = 0))
        val vivo = e.conRumbo(350)
        assertEquals(350, vivo.mapa?.rumbo)
        assertEquals(100, vivo.nav?.flechaGrados)
        assertTrue(e.conRumbo(null) === e)
    }

    @Test fun hotelConCalificacionIdaYVuelta() {
        val h = Hotel("node/1", "Hotel", "hotel", zocalo, 100, 1250, 4, calificacion = 4.3)
        val de = JSONObject(h.aJson().toString()).aHotel()
        assertEquals(4.3, de.calificacion, 1e-9)
        val viejo = JSONObject(h.aJson().apply { remove("calif") }.toString()).aHotel()
        assertEquals(0.0, viejo.calificacion, 1e-9)
    }
}
