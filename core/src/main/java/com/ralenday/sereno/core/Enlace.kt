package com.ralenday.sereno.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.roundToInt

/**
 * Comunicación teléfono <-> reloj.
 *
 * - Teléfono -> reloj: un DataItem (`/sereno/estado`) con el [EstadoReloj] completo. La Data Layer
 *   lo entrega aunque el reloj se haya desconectado un rato, y el reloj lo guarda en disco para
 *   seguir mostrando la última ruta sin conexión.
 * - Reloj -> teléfono: mensajes cortos (`/sereno/cmd`) con un [Comando].
 *
 * En emuladores sin cuenta de Google no hay emparejamiento, así que se usa además un puente TCP a
 * través del host (10.0.2.2 + `adb forward`), igual que en MemoFlash.
 */
class Enlace private constructor(private val context: Context) {

    val soyReloj: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    val esEmulador: Boolean = Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("sdk_g") || Build.HARDWARE.contains("ranchu")

    private val dataClient = Wearable.getDataClient(context)
    private val archivoUltimo = File(context.filesDir, "ultimo_estado.json")

    private val _conectado = MutableStateFlow(false)
    val conectado: StateFlow<Boolean> = _conectado

    /** Solo reloj: lo último que mandó el teléfono (o lo guardado en disco). */
    private val _estado = MutableStateFlow(leerUltimo())
    val estado: StateFlow<EstadoReloj?> = _estado

    /**
     * Solo reloj: brújula en vivo del teléfono (0..359°). Llega por un canal ligero aparte (unos bytes,
     * ~10 veces por segundo) para no reenviar todo el estado con cada grado que gira el celular.
     */
    private val _rumboVivo = MutableStateFlow<Int?>(null)
    val rumboVivo: StateFlow<Int?> = _rumboVivo

    /** Solo teléfono: comandos que llegan del reloj. */
    private val _comandos = MutableSharedFlow<Comando>(extraBufferCapacity = 32)
    val comandos: SharedFlow<Comando> = _comandos

    @Volatile private var ultimoPublicado: ByteArray? = null
    private lateinit var scope: CoroutineScope

    fun iniciar(scope: CoroutineScope) {
        this.scope = scope
        if (esEmulador) iniciarServidorLocal()
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                revisarConexion()
                delay(if (_conectado.value) 30_000 else 5_000)
            }
        }
        if (soyReloj) scope.launch(Dispatchers.IO) {
            leerDataItemsExistentes()
            enviar(Comando(TipoComando.PEDIR_ESTADO))
        }
    }

    // ---------------- Teléfono ----------------

    suspend fun publicar(estado: EstadoReloj) = withContext(Dispatchers.IO) {
        val bytes = estado.aBytes()
        ultimoPublicado = bytes
        try {
            val req = PutDataMapRequest.create(RUTA_ESTADO).apply {
                dataMap.putByteArray(CLAVE, bytes)
                dataMap.putLong("t", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(req).await()
        } catch (e: Exception) {
            Log.d(TAG, "Data Layer no disponible: ${e.message}")
        }
        if (esEmulador) {
            try {
                tcp(PUERTO_RELOJ, TIPO_ESTADO, bytes)
                _conectado.value = true
            } catch (e: Exception) {
                Log.d(TAG, "Puente: no se pudo empujar estado al reloj: ${e.message}")
            }
        }
    }

    // ---------------- Reloj ----------------

    /** Manda un comando al teléfono. Devuelve false si no se pudo entregar. */
    suspend fun enviar(cmd: Comando): Boolean = withContext(Dispatchers.IO) {
        val bytes = cmd.aBytes()
        if (esEmulador) {
            try {
                // El teléfono responde con su estado actual
                val respuesta = tcp(PUERTO_TELEFONO, TIPO_COMANDO, bytes)
                if (respuesta.isNotEmpty()) recibirEstado(respuesta)
                _conectado.value = true
                return@withContext true
            } catch (_: Exception) { }
        }
        try {
            val nodos = Wearable.getCapabilityClient(context)
                .getCapability(CAP_TELEFONO, CapabilityClient.FILTER_REACHABLE).await().nodes
            for (n in nodos) Wearable.getMessageClient(context).sendMessage(n.id, RUTA_CMD, bytes).await()
            nodos.isNotEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo enviar comando: ${e.message}")
            false
        }
    }

    internal fun recibirEstado(bytes: ByteArray) {
        try {
            val e = EstadoReloj.deBytes(bytes)
            val actual = _estado.value
            if (actual != null && e.generado < actual.generado) return
            _estado.value = e
            archivoUltimo.writeBytes(bytes)
            Log.d(TAG, "Estado recibido: ${e.hoteles.size} hoteles, ${e.rutas.size} rutas, nav=${e.nav != null}")
        } catch (ex: Exception) {
            Log.w(TAG, "Estado inválido", ex)
        }
    }

    internal fun recibirComando(bytes: ByteArray) {
        try {
            _comandos.tryEmit(Comando.deBytes(bytes))
        } catch (ex: Exception) {
            Log.w(TAG, "Comando inválido", ex)
        }
    }

    private fun leerUltimo(): EstadoReloj? = try {
        if (archivoUltimo.exists()) EstadoReloj.deBytes(archivoUltimo.readBytes()) else null
    } catch (_: Exception) {
        null
    }

    private suspend fun leerDataItemsExistentes() {
        try {
            val items = dataClient.getDataItems(Uri.parse("wear://*$RUTA_ESTADO")).await()
            try {
                for (item in items) DataMapItem.fromDataItem(item).dataMap.getByteArray(CLAVE)?.let { recibirEstado(it) }
            } finally {
                items.release()
            }
        } catch (_: Exception) { }
    }

    private suspend fun revisarConexion() {
        val cap = if (soyReloj) CAP_TELEFONO else CAP_RELOJ
        val nodos = try {
            Wearable.getCapabilityClient(context).getCapability(cap, CapabilityClient.FILTER_REACHABLE).await().nodes
        } catch (_: Exception) {
            emptySet()
        }
        _conectado.value = nodos.isNotEmpty() || (esEmulador && pingLocal())
    }

    // ---------------- Puente TCP para emuladores ----------------

    private fun iniciarServidorLocal() {
        val puerto = if (soyReloj) PUERTO_RELOJ else PUERTO_TELEFONO
        scope.launch(Dispatchers.IO) {
            try {
                ServerSocket(puerto, 8, InetAddress.getByName("0.0.0.0")).use { servidor ->
                    while (isActive) {
                        val cliente = servidor.accept()
                        scope.launch(Dispatchers.IO) { atender(cliente) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo abrir el puerto $puerto", e)
            }
        }
    }

    /**
     * Atiende una conexión del puente. Puede traer varios mensajes seguidos: el canal de brújula deja la
     * conexión abierta y manda un mensaje corto por cada lectura, así se evita abrir un socket cada vez.
     */
    private fun atender(cliente: Socket) {
        try {
            cliente.use { s ->
                s.soTimeout = TIMEOUT_MS
                s.tcpNoDelay = true
                val entrada = DataInputStream(s.getInputStream())
                val salida = DataOutputStream(s.getOutputStream())
                while (true) {
                    val tipo = entrada.read()
                    if (tipo < 0) break // el otro lado cerró la conexión
                    val datos = ByteArray(entrada.readInt()).also { entrada.readFully(it) }
                    val respuesta: ByteArray = when (tipo) {
                        TIPO_ESTADO -> { recibirEstado(datos); ByteArray(0) }
                        TIPO_COMANDO -> {
                            val cmd = Comando.deBytes(datos)
                            if (cmd.tipo != TipoComando.PEDIR_ESTADO) recibirComando(datos)
                            ultimoPublicado ?: ByteArray(0)
                        }
                        TIPO_RUMBO -> {
                            recibirRumbo(datos)
                            // Conexión persistente: tolerar pausas largas cuando el celular está quieto
                            s.soTimeout = TIMEOUT_RUMBO_MS
                            null
                        }
                        else -> ByteArray(0)
                    } ?: continue
                    salida.writeInt(respuesta.size)
                    salida.write(respuesta)
                    salida.flush()
                    _conectado.value = true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Conexión local terminada: ${e.message}")
        }
    }

    internal fun recibirRumbo(datos: ByteArray) {
        val r = String(datos, Charsets.UTF_8).trim().toDoubleOrNull() ?: return
        _rumboVivo.value = ((r.roundToInt() % 360) + 360) % 360
        _conectado.value = true
    }

    private fun pingLocal(): Boolean = try {
        val puerto = if (soyReloj) PUERTO_TELEFONO else PUERTO_RELOJ
        Socket().use { it.connect(InetSocketAddress(HOST_EMULADOR, puerto), TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    private fun tcp(puerto: Int, tipo: Int, bytes: ByteArray): ByteArray = Socket().use { s ->
        s.connect(InetSocketAddress(HOST_EMULADOR, puerto), TIMEOUT_MS)
        s.soTimeout = TIMEOUT_MS
        val salida = DataOutputStream(s.getOutputStream())
        val entrada = DataInputStream(s.getInputStream())
        salida.writeByte(tipo)
        salida.writeInt(bytes.size)
        salida.write(bytes)
        salida.flush()
        ByteArray(entrada.readInt()).also { entrada.readFully(it) }
    }

    companion object {
        private const val TAG = "SerenoEnlace"
        const val RUTA_ESTADO = "/sereno/estado"
        const val RUTA_CMD = "/sereno/cmd"
        const val CLAVE = "e"
        const val CAP_TELEFONO = "sereno_telefono"
        const val CAP_RELOJ = "sereno_reloj"

        private const val HOST_EMULADOR = "10.0.2.2"
        const val PUERTO_TELEFONO = 19886
        const val PUERTO_RELOJ = 19887
        private const val TIMEOUT_MS = 2_500
        private const val TIPO_ESTADO = 1
        private const val TIPO_COMANDO = 2
        private const val TIPO_RUMBO = 3
        private const val TIMEOUT_RUMBO_MS = 120_000

        @Volatile private var instancia: Enlace? = null

        fun get(context: Context): Enlace = instancia ?: synchronized(this) {
            instancia ?: Enlace(context.applicationContext).also { instancia = it }
        }
    }
}
