package com.ralenday.sereno.mobile.logica

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.ralenday.sereno.core.Punto
import com.ralenday.sereno.mobile.R
import com.ralenday.sereno.mobile.ui.MainActivity

/**
 * Mantiene el GPS del TELÉFONO activo con la pantalla apagada (el teléfono va guardado).
 * Usa LocationManager del sistema: gratis y sin depender de Google Play Services.
 * En emulador la posición se cambia con `adb emu geo fix <lon> <lat>` o con el simulador de la app.
 */
class ServicioViaje : Service(), LocationListener {

    private lateinit var lm: LocationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            stopSelf()
            return START_NOT_STICKY
        }
        val noti = notificacion()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTI, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID_NOTI, noti)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lm.removeUpdates(this)
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(prov)) {
                    // 3 s / 5 m: suficiente para giro por giro a pie sin agotar la batería
                    lm.requestLocationUpdates(prov, 3_000L, 5f, this, Looper.getMainLooper())
                    lm.getLastKnownLocation(prov)?.let { onLocationChanged(it) }
                }
            }
        }
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        val rumbo = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f) loc.bearing.toDouble() else null
        Motor.get().nuevaUbicacion(Punto(loc.latitude, loc.longitude), rumbo)
    }

    override fun onDestroy() {
        lm.removeUpdates(this)
        super.onDestroy()
    }

    private fun notificacion(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Viaje activo", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val detener = PendingIntent.getService(
            this, 1, Intent(this, ServicioViaje::class.java).setAction(ACCION_DETENER), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sereno te acompaña")
            .setContentText("Guarda el teléfono: sigue las indicaciones en tu reloj")
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Terminar viaje", detener).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CANAL = "viaje"
        private const val ID_NOTI = 7
        private const val ACCION_DETENER = "detener"

        fun iniciar(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            context.startForegroundService(Intent(context, ServicioViaje::class.java))
        }

        fun detener(context: Context) {
            context.stopService(Intent(context, ServicioViaje::class.java))
        }
    }
}
