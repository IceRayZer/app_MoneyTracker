package com.icerayzer.moneytracker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Synchronisation entre deux téléphones jumelés, en Bluetooth classique (RFCOMM), sans internet.
 * Le lien est authentifié et chiffré par le jumelage Android (socket « sécurisé ») ; ici on ne fait que transporter
 * du JSON, la fusion se fait dans l'interface (mergeData). Échange, en trames (voir Frames.kt) :
 *   hôte → client : ses données, une fois que l'utilisateur de l'hôte a accepté ;
 *   client → hôte : les siennes ; hôte → client : « ok ». Chacun fusionne ensuite de son côté.
 * ponytail: pas de chiffrement en plus de celui du lien Bluetooth. Ajouter un échange de clés avec code à comparer
 * si l'on veut résister à un attaquant qui sait casser le jumelage Bluetooth lui-même.
 */
class BluetoothSync(private val activity: MainActivity) {
    private class Session {
        private val sockets = mutableListOf<Closeable>()
        val approval = CountDownLatch(1)
        @Volatile var approved = false
        @Volatile var done = false
        val timeout = Runnable { close() }

        fun add(c: Closeable) = synchronized(this) { sockets += c }
        fun close() = synchronized(this) { sockets.toList() }.forEach { runCatching { it.close() } }
    }

    @Volatile private var current: Session? = null
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = activity.getSystemService(BluetoothManager::class.java)?.adapter

    private fun permitted() = Build.VERSION.SDK_INT < 31 ||
        activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun error(code: String) = JSONObject().put("error", code).toString()

    /** JSON : { devices: [{ name, address }] } ou { error: permission | off | unsupported }. Seuls les appareils jumelés. */
    @SuppressLint("MissingPermission") // permitted() est vérifié juste avant
    fun peers(): String {
        val a = adapter ?: return error("unsupported")
        if (!permitted()) {
            activity.requestBluetooth()
            return error("permission")
        }
        if (!a.isEnabled) return error("off")
        val list = JSONArray()
        for (d in a.bondedDevices.orEmpty()) list.put(JSONObject().put("name", d.name ?: d.address).put("address", d.address))
        return JSONObject().put("devices", list).toString()
    }

    /** Ce téléphone attend qu'un autre se connecte. [payload] : ses données (JSON). */
    @SuppressLint("MissingPermission")
    fun host(payload: String) {
        val a = adapter ?: return
        if (!permitted() || !a.isEnabled) return
        val s = begin()
        thread(name = "bt-host") {
            try {
                val server = a.listenUsingRfcommWithServiceRecord("MoneyTracker", SERVICE_UUID).also(s::add)
                val socket = server.accept().also(s::add) // bloque jusqu'à une connexion ou l'annulation
                server.close()
                handler.postDelayed(s.timeout, TIMEOUT_MS)
                val peer = socket.remoteDevice
                if (peer.bondState != BluetoothDevice.BOND_BONDED) return@thread fail(s, "refused")

                emit(s, "onSyncPeer(${JSONObject.quote(peer.name ?: peer.address)})")
                if (!s.approval.await(APPROVAL_SECONDS, TimeUnit.SECONDS) || !s.approved) return@thread fail(s, "refused")

                emit(s, "onSyncStatus('transfer')")
                val out = DataOutputStream(socket.outputStream)
                val input = DataInputStream(socket.inputStream)
                writeFrame(out, payload)
                val remote = readFrame(input)
                writeFrame(out, "ok")
                succeed(s, remote, peer.name ?: peer.address)
                // On laisse le client fermer le premier : fermer tout de suite pourrait couper le « ok » en route.
                runCatching { input.read() }
            } catch (e: IOException) {
                fail(s, "failed")
            } catch (e: SecurityException) {
                fail(s, "permission")
            } finally {
                handler.removeCallbacks(s.timeout)
                s.close()
            }
        }
    }

    /** Se connecte à l'appareil jumelé [address] (qui doit avoir ouvert l'écran de synchro). */
    @SuppressLint("MissingPermission")
    fun connect(address: String, payload: String) {
        val a = adapter ?: return
        if (!permitted() || !a.isEnabled) return
        val s = begin()
        thread(name = "bt-client") {
            try {
                // Uniquement un appareil déjà jumelé : une adresse quelconque venue de la page est refusée.
                val device = a.bondedDevices.orEmpty().firstOrNull { it.address == address } ?: return@thread fail(s, "failed")
                emit(s, "onSyncStatus('connecting')")
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID).also(s::add)
                socket.connect()
                handler.postDelayed(s.timeout, TIMEOUT_MS)

                emit(s, "onSyncStatus('waiting')")
                val out = DataOutputStream(socket.outputStream)
                val input = DataInputStream(socket.inputStream)
                val remote = readFrame(input) // n'arrive que si l'autre téléphone a accepté
                emit(s, "onSyncStatus('transfer')")
                writeFrame(out, payload)
                if (readFrame(input) != "ok") throw IOException("accusé de réception manquant")
                succeed(s, remote, device.name ?: address)
            } catch (e: IOException) {
                fail(s, "failed")
            } catch (e: SecurityException) {
                fail(s, "permission")
            } finally {
                handler.removeCallbacks(s.timeout)
                s.close()
            }
        }
    }

    /** Réponse de l'utilisateur de l'hôte à « Accepter ? ». */
    fun approve(ok: Boolean) {
        current?.let {
            it.approved = ok
            it.approval.countDown()
        }
    }

    fun cancel() {
        current?.close()
        current = null
    }

    // Une seule session à la fois : en commencer une arrête la précédente (par exemple l'attente quand on se connecte).
    @Synchronized
    private fun begin(): Session {
        current?.close()
        return Session().also { current = it }
    }

    // Les sessions arrêtées ou remplacées ne parlent plus à l'interface.
    private fun emit(s: Session, js: String) {
        if (current === s) activity.js(js)
    }

    private fun succeed(s: Session, remote: String, peer: String) {
        if (current !== s) return
        s.done = true
        activity.js("onSyncData(${JSONObject.quote(remote)}, ${JSONObject.quote(peer)})")
    }

    private fun fail(s: Session, code: String) {
        if (current !== s || s.done) return
        activity.js("onSyncError('$code')")
    }

    private companion object {
        // Identifiant du service MoneyTracker : les deux téléphones doivent utiliser le même.
        val SERVICE_UUID: UUID = UUID.fromString("6f5b2d3e-8a41-4c27-9d0e-3c1a7b5e9f42")
        const val APPROVAL_SECONDS = 90L
        const val TIMEOUT_MS = 150_000L // accord (90 s) + transfert
    }
}
