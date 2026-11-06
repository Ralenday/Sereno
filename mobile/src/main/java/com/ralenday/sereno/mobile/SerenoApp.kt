package com.ralenday.sereno.mobile

import android.app.Application
import com.ralenday.sereno.core.Enlace
import com.ralenday.sereno.mobile.logica.Motor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SerenoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Se inicia aquí (no en la Activity) para atender comandos del reloj con la app cerrada
        Enlace.get(this).iniciar(scope)
        Motor.crear(this, scope)
    }
}
