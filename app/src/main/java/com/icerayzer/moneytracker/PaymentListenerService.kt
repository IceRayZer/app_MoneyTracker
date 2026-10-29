package com.icerayzer.moneytracker

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Lit les notifications des apps de [PAYMENT_APPS], en extrait montant et commerçant,
 * et met le résultat en attente de validation. Le texte brut de la notification n'est jamais stocké.
 */
class PaymentListenerService : NotificationListenerService() {
    // ponytail: dédoublonnage en mémoire (même paquet + même texte à moins de 2 min), perdu si le service redémarre ; suffisant pour les doublons immédiats.
    private val seen = LinkedHashMap<Int, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = PAYMENT_APPS[sbn.packageName] ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val payment = parsePayment(title, text, sbn.packageName) ?: return

        val now = System.currentTimeMillis()
        val key = "${sbn.packageName}|$title|$text".hashCode()
        synchronized(seen) {
            val last = seen.put(key, now)
            if (last != null && now - last < 120_000) return
            if (seen.size > 200) seen.remove(seen.keys.first())
        }
        PaymentStore.add(this, payment, source, now)
    }
}

/** File des paiements à valider, dans les préférences privées de l'app. Lue par l'interface via [Bridge]. */
object PaymentStore {
    const val PREFS = "payments"
    private const val KEY = "pending"
    private const val MAX = 100 // au-delà, les plus anciens non validés sont abandonnés

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(c: Context): String = prefs(c).getString(KEY, null) ?: "[]"

    // Une file illisible est remplacée par une vide plutôt que de bloquer le service à chaque notification.
    private fun read(c: Context) = runCatching { JSONArray(list(c)) }.getOrElse { JSONArray() }

    @Synchronized
    fun add(c: Context, p: Payment, source: String, time: Long) {
        val all = read(c)
        all.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("amount", p.amount)
                .put("merchant", p.merchant)
                .put("source", source)
                .put("time", time)
        )
        while (all.length() > MAX) all.remove(0)
        prefs(c).edit().putString(KEY, all.toString()).apply()
    }

    @Synchronized
    fun ack(c: Context, id: String) {
        val all = read(c)
        val kept = JSONArray()
        for (i in 0 until all.length()) all.getJSONObject(i).takeIf { it.optString("id") != id }?.let(kept::put)
        prefs(c).edit().putString(KEY, kept.toString()).apply()
    }
}
