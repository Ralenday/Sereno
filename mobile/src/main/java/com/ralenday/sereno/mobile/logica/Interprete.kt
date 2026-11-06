package com.ralenday.sereno.mobile.logica

import com.ralenday.sereno.core.Orden
import java.text.Normalizer

data class Peticion(
    val tipos: Set<String> = emptySet(),
    val presupuestoMax: Int? = null,
    val barato: Boolean = false,
    val orden: Orden = Orden.DISTANCIA,
    val cercaDelCentro: Boolean = false,
    /** "cerca de la central de autobuses" -> "central de autobuses" */
    val cercaDe: String? = null,
)

/**
 * Entiende frases como "Necesito hotel barato cerca del centro y transporte público"
 * con reglas simples (sin IA de pago). Funciona igual con texto escrito o dictado.
 */
object Interprete {

    fun interpretar(frase: String): Peticion {
        val t = normalizar(frase)
        val tipos = buildSet {
            if (Regex("\\bhostal|hostel|albergue").containsMatchIn(t)) add("hostel")
            if (Regex("\\bmotel").containsMatchIn(t)) add("motel")
            if (Regex("airbnb|departamento|depa\\b|casa de huespedes|posada").containsMatchIn(t)) { add("guest_house"); add("apartment") }
            if (Regex("\\bhotel").containsMatchIn(t)) add("hotel")
        }
        val numero = Regex("(?:menos de|maximo|hasta|\\$)\\s*\\$?\\s*(\\d{3,5})").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val barato = Regex("barat|economic|bajo costo|accesible|poco dinero").containsMatchIn(t)
        val orden = when {
            Regex("mejor valorad|bueno|lujo|estrellas|calidad").containsMatchIn(t) -> Orden.VALORACION
            barato || numero != null -> Orden.PRECIO
            else -> Orden.DISTANCIA
        }
        val centro = Regex("\\bcentro\\b").containsMatchIn(t) && !Regex("centro comercial|centro de convenciones").containsMatchIn(t)
        val cercaDe = Regex("cerca (?:de|del|de la|de los|de las) (.+?)(?: y |,|\\.|$)").find(t)?.groupValues?.get(1)
            ?.removePrefix("la ")?.removePrefix("el ")?.trim()
            ?.takeIf { it.isNotBlank() && it != "centro" && it != "aqui" && it != "mi" }
        return Peticion(tipos, numero, barato, orden, centro, cercaDe)
    }

    fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
