package com.ralenday.sereno.mobile.logica

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.ralenday.sereno.core.Punto
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Funciones de seguridad que no cuestan nada: SMS a contactos de confianza (sin servidor) y un
 * historial local de la ruta. Los SMS sí pueden tener costo según el plan del usuario.
 */
class Seguridad(private val context: Context) {

    private val prefs = context.getSharedPreferences("seguridad", Context.MODE_PRIVATE)
    private val historial = File(context.filesDir, "historial_ruta.csv")
    private var ultimoHistorial = 0L

    var contactos: List<String>
        get() = prefs.getString("contactos", "").orEmpty().split(";").filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("contactos", v.joinToString(";")).apply()

    var nombre: String
        get() = prefs.getString("nombre", "").orEmpty()
        set(v) = prefs.edit().putString("nombre", v).apply()

    var minutosQuieto: Int
        get() = prefs.getInt("minQuieto", 10)
        set(v) = prefs.edit().putInt("minQuieto", v).apply()

    var detectarQuieto: Boolean
        get() = prefs.getBoolean("detectarQuieto", true)
        set(v) = prefs.edit().putBoolean("detectarQuieto", v).apply()

    var avisosRiesgo: Boolean
        get() = prefs.getBoolean("avisosRiesgo", true)
        set(v) = prefs.edit().putBoolean("avisosRiesgo", v).apply()

    var preferirIluminadas: Boolean
        get() = prefs.getBoolean("iluminadas", true)
        set(v) = prefs.edit().putBoolean("iluminadas", v).apply()

    fun puedeEnviarSms() =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Devuelve a cuántos contactos se envió. */
    fun enviarATodos(texto: String): Int {
        if (!puedeEnviarSms()) return 0
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        var enviados = 0
        for (c in contactos) {
            try {
                sms.sendMultipartTextMessage(c, null, sms.divideMessage(texto), null, null)
                enviados++
            } catch (e: Exception) {
                Log.w("SerenoSeguridad", "SMS a $c falló", e)
            }
        }
        return enviados
    }

    fun mensajePanico(p: Punto?) = "SERENO - ${nombre.ifBlank { "Tu contacto" }} activó el botón de AYUDA. " + ubicacion(p)

    fun mensajeEnVivo(p: Punto?) = "SERENO - ${nombre.ifBlank { "Tu contacto" }} comparte su ubicación: " + ubicacion(p)

    fun mensajeQuieto(p: Punto?, min: Int) =
        "SERENO - ${nombre.ifBlank { "Tu contacto" }} lleva $min min sin moverse y no respondió. " + ubicacion(p)

    private fun ubicacion(p: Punto?): String {
        val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return if (p == null) "Ubicación desconocida ($hora)"
        else "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=18/%.5f/%.5f (%s)"
            .format(Locale.US, p.lat, p.lon, p.lat, p.lon, hora)
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
