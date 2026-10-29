package com.icerayzer.moneytracker

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface

/** Pont exposé à l'interface sous `window.Bridge`. Seule la page embarquée (assets) est chargée. */
class Bridge(private val activity: MainActivity) {
    /** JSON : [{ id, amount, merchant, source, time }] */
    @JavascriptInterface
    fun pendingPayments(): String = PaymentStore.list(activity)

    /** Retire un paiement de la file (validé ou ignoré). */
    @JavascriptInterface
    fun ack(id: String) = PaymentStore.ack(activity, id)

    @JavascriptInterface
    fun hasNotifAccess(): Boolean {
        val component = ComponentName(activity, PaymentListenerService::class.java)
        if (Build.VERSION.SDK_INT >= 27) {
            return activity.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        }
        return runCatching {
            Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
                ?.contains(component.flattenToString()) == true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun openNotifSettings() = activity.runOnUiThread {
        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Ouvre le sélecteur de fichiers Android pour enregistrer la sauvegarde (Drive, Téléchargements…). */
    @JavascriptInterface
    fun saveFile(name: String, content: String) = activity.runOnUiThread { activity.exportFile(name, content) }

    // ---- Synchro avec un autre téléphone (Bluetooth) : voir BluetoothSync ----
    @JavascriptInterface
    fun syncPeers(): String = activity.sync.peers()

    @JavascriptInterface
    fun syncHost(payload: String) = activity.sync.host(payload)

    @JavascriptInterface
    fun syncConnect(address: String, payload: String) = activity.sync.connect(address, payload)

    @JavascriptInterface
    fun syncApprove(ok: Boolean) = activity.sync.approve(ok)

    @JavascriptInterface
    fun syncCancel() = activity.sync.cancel()

    @JavascriptInterface
    fun openBluetoothSettings() = activity.runOnUiThread {
        activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    /** Reconnaissance vocale d'Android ; le résultat revient par `window.onSpeech(texte)`. */
    @JavascriptInterface
    fun listen() = activity.runOnUiThread { activity.listen() }
}
