package com.ralenday.sereno.mobile.datos

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP mínimo. Los servicios públicos de OSM piden identificarse con un User-Agent propio
 * y no abusar (Nominatim: 1 petición/seg). Ver políticas de uso en CLAUDE.md.
 */
object Http {
    private const val AGENTE = "Sereno/0.1 (proyecto escolar; Android)"

    fun get(url: String, timeoutMs: Int = 30_000): String = abrir(url, timeoutMs).let { c ->
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} en ${URL(url).host}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun postForm(url: String, campos: Map<String, String>, timeoutMs: Int = 60_000): String {
        val c = abrir(url, timeoutMs)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val cuerpo = campos.entries.joinToString("&") { "${it.key}=${cod(it.value)}" }
            c.outputStream.use { it.write(cuerpo.toByteArray()) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} en ${URL(url).host}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun cod(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun abrir(url: String, timeoutMs: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", AGENTE)
        setRequestProperty("Accept-Language", "es-MX,es")
    }
}
