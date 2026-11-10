package com.ralenday.sereno.wear

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.ralenday.sereno.core.Enlace
import com.ralenday.sereno.core.Giro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RelojApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Enlace.get(this).iniciar(scope)
    }
}

/**
 * Lenguaje de vibraciones para navegar sin mirar (documentado en la pantalla de ayuda):
 * - Izquierda: 2 toques cortos · Derecha: 3 toques cortos
 * - Sigue recto / ligera: 1 toque · Vuelta en U: 1 largo + 2 cortos
 * - Llegada: 1 vibración larga · Zona de riesgo: 3 largas · Tu parada: 2 cortas + 1 larga
 */
class Vibracion(context: Context) {
    private val vibrador = context.getSystemService(Vibrator::class.java)

    fun toque() = patron(0, 40)
    fun giro(g: Giro) = when (g) {
        Giro.IZQ, Giro.FUERTE_IZQ -> patron(0, 90, 120, 90)
        Giro.DER, Giro.FUERTE_DER -> patron(0, 90, 120, 90, 120, 90)
        Giro.LIGERA_IZQ, Giro.LIGERA_DER, Giro.RECTO, Giro.ROTONDA, Giro.SALIDA -> patron(0, 70)
        Giro.VUELTA_U -> patron(0, 400, 150, 90, 120, 90)
        Giro.LLEGADA -> patron(0, 700)
    }
    /** Zona de robos / riesgo: 3 largas. */
    fun alerta() = patron(0, 450, 200, 450, 200, 450)
    /** Prepárate para bajar del transporte: 2 cortas + 1 larga. */
    fun bajada() = patron(0, 120, 120, 120, 300, 600)
    /** Desvío o inactividad: 2 medias. */
    fun aviso() = patron(0, 250, 180, 250)

    private fun patron(vararg t: Long) {
        if (vibrador?.hasVibrator() == true) vibrador.vibrate(VibrationEffect.createWaveform(t, -1))
    }
}
