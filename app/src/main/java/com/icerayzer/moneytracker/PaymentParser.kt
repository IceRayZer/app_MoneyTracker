package com.icerayzer.moneytracker

import kotlin.math.abs

/**
 * Mouvement d'argent extrait d'une notification.
 * [merchant] : commerçant, bénéficiaire ou émetteur (peut être vide : l'utilisateur complète).
 */
data class Payment(
    val amount: Double,
    val merchant: String,
    val credit: Boolean = false,   // argent reçu (virement reçu, remboursement) ; sinon argent sorti
    val transfer: Boolean = false, // virement, et non paiement par carte
    val card: String = "",         // 4 derniers chiffres de la carte, si la notification les donne
    val currency: String = "EUR",  // autre devise : paiement à l'étranger, l'utilisateur corrige le montant en euros
)

/**
 * Apps dont les notifications sont lues, avec le nom affiché dans la file « à valider ».
 * Pour une autre banque : ajouter ici son nom de paquet (visible dans l'URL Play Store, `?id=...`).
 */
val PAYMENT_APPS = mapOf(
    "com.google.android.apps.walletnfcrel" to "Google Wallet",
    "com.google.android.gms" to "Google Wallet", // certains reçus Wallet sont postés par les services Google Play
    "com.caisseepargne.android.mobilebanking" to "Caisse d'Épargne", // app « Banxo »
    "de.number26.android" to "N26",
    "com.revolut.revolut" to "Revolut",
)

// Pour Wallet, le titre de la notification est le nom du commerçant (« TRADER JOE S #1234 »).
private val TITLE_IS_MERCHANT = setOf("com.google.android.apps.walletnfcrel", "com.google.android.gms")

// Montant : « 12,50 », « 1 234,00 » (espaces fines comprises), « 1.234,56 », « 8.40 ».
// Pas de chiffre, « / », « : », « # » ni séparateur juste avant : « le 12/03 250,00 € » ne donne pas 3 250 €,
// et le numéro de magasin « #1234 » n'est pas un montant.
private const val NUMBER = """(?<![\d/:.,#])(\d{1,3}(?:[ \u00A0\u202F.,]\d{3})+|\d+)(?:[.,](\d{1,2}))?(?!\d)"""
private const val SPACE = """[ \t\u00A0\u202F]*""" // espaces insécables compris, mais jamais un saut de ligne
private const val CURRENCY = """(€|EUR\b|\$|USD\b|£|GBP\b|CHF\b)"""
private val AMOUNT_AFTER = Regex("""$NUMBER$SPACE$CURRENCY""", RegexOption.IGNORE_CASE)  // 1 entier, 2 décimales, 3 devise
private val AMOUNT_BEFORE = Regex("""$CURRENCY$SPACE$NUMBER""", RegexOption.IGNORE_CASE) // 1 devise, 2 entier, 3 décimales

// Un montant juste après « solde » est un solde, pas un mouvement.
private val BALANCE_BEFORE = Regex("""(?:solde|balance)[^\d\n]{0,25}$""", RegexOption.IGNORE_CASE)

// Carte : « Visa •••• 1234 », « ••1234 », « **** 1234 », « XXXX1234 ».
private val CARD = Regex("""[•∙·*xX]{2,}\s?(\d{4})(?!\d)""")

// Mots entiers, accents compris (\b ne marche pas après « é »).
private fun words(vararg w: String) = Regex("""(?<!\p{L})(?:${w.joinToString("|")})(?!\p{L})""", RegexOption.IGNORE_CASE)

private val TRANSFER = words("virement", "transfert", "transfer")
private val CREDIT = words(
    "reçu", "reçue", "recu", "recue", "crédité", "créditée", "credite", "creditee",
    "vous a envoyé", "vous a viré", "vous a versé", "remboursement", "remboursé", "remboursée",
    "received", "refund", "refunded",
)
// Contredit « reçu » : « Nous avons reçu votre virement vers X », « débité (remboursement de prêt) ».
private val OUTGOING = words(
    "vers", "envoyé à", "envoyée à", "à destination de", "émis", "émise", "sent to",
    "débité", "débitée", "prélevé", "prélevée", "prélèvement",
)
// Aucun argent n'a bougé : on ignore.
private val DECLINED = words(
    "refusé", "refusée", "refus", "décliné", "déclinée", "declined", "échoué", "échouée", "échec", "failed",
    "rejeté", "rejetée", "rejected", "annulé", "annulée", "cancelled", "canceled", "insuffisant", "insuffisante",
)

// Fin du nom de l'autre partie : une date, une ponctuation, un chiffre, la fin de ligne.
private const val END = """\s*(?=\s(?:le|du|on|avec|with|par|via|pour|for|sur)\s|[.,;:!]\s|[.,;:!(]?$|\(|\s\d)"""
private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
// Argent sorti : « chez Fnac », « à DARTY », « vers Jean Dupont », « at Starbucks », « to John ».
private val PAYEE = Regex("""(?:^|\s)(?:chez|à|at|vers|to)\s+([^\d\s].*?)$END""", OPTS)
// Argent reçu : « de Jean Dupont », « de la part de ACME », « from John », « par Amazon », « Jean vous a envoyé ».
private val PAYER = Regex("""(?:^|\s)(?:de la part de|de|from|par)\s+([^\d\s].*?)$END""", OPTS)
private val PAYER_FIRST = Regex("""^(.+?)\s+vous a (?:envoyé|viré|versé)""", OPTS)

private class Found(val at: Int, val int: String, val dec: String, val cur: String)

/** Renvoie null si aucun montant n'est trouvé ou si rien n'a bougé (paiement refusé, solde) : on ne devine pas. */
fun parsePayment(title: String?, text: String?, pkg: String): Payment? {
    val t = title.orEmpty().trim()
    val all = listOf(t, text.orEmpty().trim()).filter { it.isNotEmpty() }.joinToString("\n")
    val credit = CREDIT.containsMatchIn(all) && !OUTGOING.containsMatchIn(all)
    if (DECLINED.containsMatchIn(all) && !credit) return null

    // Le premier montant qui n'est pas un solde est le mouvement (un solde éventuel vient souvent après).
    val found = AMOUNT_AFTER.findAll(all).map { Found(it.range.first, it.groupValues[1], it.groupValues[2], it.groupValues[3]) } +
        AMOUNT_BEFORE.findAll(all).map { Found(it.range.first, it.groupValues[2], it.groupValues[3], it.groupValues[1]) }
    val m = found.sortedBy { it.at }.firstOrNull { !BALANCE_BEFORE.containsMatchIn(all.substring(0, it.at)) } ?: return null
    val amount = (m.int.filter(Char::isDigit) + "." + m.dec.ifEmpty { "0" }).toDouble()
    if (amount <= 0) return null
    val currency = when (m.cur.uppercase()) {
        "€", "EUR" -> "EUR"
        "$", "USD" -> "USD"
        "£", "GBP" -> "GBP"
        else -> "CHF"
    }

    val other = (if (credit) PAYER.find(all) ?: PAYER_FIRST.find(all) else null)?.groupValues?.get(1)?.trim()
        ?: PAYEE.find(all)?.groupValues?.get(1)?.trim()
        ?: t.takeIf { pkg in TITLE_IS_MERCHANT && !AMOUNT_AFTER.containsMatchIn(it) && !AMOUNT_BEFORE.containsMatchIn(it) }
            ?.replace(Regex("""\s*#\s*\S*$"""), "")?.trim() // « TRADER JOE S #1234 » : numéro de magasin retiré
        ?: ""
    return Payment(
        amount = amount,
        merchant = other.take(60),
        credit = credit,
        transfer = TRANSFER.containsMatchIn(all),
        card = CARD.find(all)?.groupValues?.get(1).orEmpty(),
        currency = currency,
    )
}

/**
 * Même mouvement signalé par deux apps (Google Wallet et l'app N26 pour un même paiement) :
 * même montant, même sens, à 3 minutes près.
 */
fun sameMovement(a: Payment, aTime: Long, b: Payment, bTime: Long) =
    a.amount == b.amount && a.credit == b.credit && abs(aTime - bTime) < 3 * 60_000
