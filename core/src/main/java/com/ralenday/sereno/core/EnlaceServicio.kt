package com.ralenday.sereno.core

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Android lo despierta cuando llega algo del otro dispositivo, aunque la app esté cerrada. */
class EnlaceServicio : WearableListenerService() {

    override fun onDataChanged(eventos: DataEventBuffer) {
        val enlace = Enlace.get(this)
        if (!enlace.soyReloj) return
        eventos.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == Enlace.RUTA_ESTADO }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getByteArray(Enlace.CLAVE) }
            .forEach { enlace.recibirEstado(it) }
    }

    override fun onMessageReceived(evento: MessageEvent) {
        val enlace = Enlace.get(this)
        if (!enlace.soyReloj && evento.path == Enlace.RUTA_CMD) enlace.recibirComando(evento.data)
    }
}
