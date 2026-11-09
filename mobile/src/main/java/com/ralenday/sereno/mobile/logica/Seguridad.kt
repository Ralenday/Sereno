package com.ralenday.sereno.mobile.logica

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ralenday.sereno.core.Punto
import java.io.File

/**
 * Funciones de seguridad locales y gratuitas: preferencias, historial de la ruta en el teléfono y
 * marcar al 911. (El envío de SMS a contactos se descartó del alcance del proyecto.)
 */
class Seguridad(private val context: Context) {

    private val prefs = context.getSharedPreferences("seguridad", Context.MODE_PRIVATE)
    private val historial = File(context.filesDir, "historial_ruta.csv")
    private var ultimoHistorial = 0L

    var minutosQuieto: Int
        get() = prefs.getInt("minQuieto", 10)
        set(v) = prefs.edit().putInt("minQuieto", v).apply()

    var detectarQuieto: Boolean
        get() = prefs.getBoolean("detectarQuieto", true)
        set(v) = prefs.edit().putBoolean("detectarQuieto", v).apply()

    var avisosRiesgo: Boolean
        get() = prefs.getBoolean("avisosRiesgo", true)
        set(v) = prefs.edit().putBoolean("avisosRiesgo", v).apply()

    /** Abre el marcador con el 911 (no llama solo: el usuario confirma). */
    fun marcar911() {
        val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Exception) { }
    }

    /** Guarda la ruta recorrida (máx. un punto cada 20 s) por si se necesita después. */
    fun registrar(p: Punto) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoHistorial < 20_000) return
        ultimoHistorial = ahora
        if (historial.length() > 2_000_000) historial.delete()
        historial.appendText("$ahora,${p.lat},${p.lon}\n")
    }

    fun puntosHistorial(): Int = if (historial.exists()) historial.readLines().size else 0

    fun borrarHistorial() { historial.delete() }
}
