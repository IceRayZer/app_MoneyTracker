package com.icerayzer.moneytracker

/** Paiement extrait d'une notification. [merchant] peut être vide : l'utilisateur complète. */
data class Payment(val amount: Double, val merchant: String)

/**
 * Apps dont les notifications sont lues, avec le nom affiché dans la file « à valider ».
 * Pour une autre banque : ajouter ici son nom de paquet (visible dans l'URL Play Store, `?id=...`).
 */
val PAYMENT_APPS = mapOf(
    "com.google.android.apps.walletnfcrel" to "Google Wallet",
    "com.google.android.gms" to "Google Wallet", // certains reçus Wallet sont postés par les services Google Play
    "com.caisseepargne.android.mobilebanking" to "Caisse d'Épargne",
    "de.number26.android" to "N26",
)

// Pour Wallet, le titre de la notification est le nom du commerçant.
private val TITLE_IS_MERCHANT = setOf("com.google.android.apps.walletnfcrel", "com.google.android.gms")

// Montant : « 12,50 », « 1 234,00 » (espaces fines comprises), « 1.234,56 », « 8.40 ».
// Pas de chiffre, « / », « : » ni séparateur juste avant : « le 12/03 250,00 € » ne donne pas 3 250 €.
private const val NUMBER = """(?<![\d/:.,])(\d{1,3}(?:[   .,]\d{3})+|\d+)(?:[.,](\d{1,2}))?(?!\d)"""
private const val SPACE = """[\s\u00A0\u202F]*""" // \s ne couvre pas les espaces insécables
private val AMOUNT_AFTER = Regex("""$NUMBER$SPACE(?:€|EUR\b)""", RegexOption.IGNORE_CASE)
private val AMOUNT_BEFORE = Regex("""(?:€|EUR)$SPACE$NUMBER""", RegexOption.IGNORE_CASE)

// Commerçant : texte après « chez », « à », « at », « vers », « to », jusqu'à une date, une ponctuation ou un chiffre.
private val MERCHANT = Regex(
    """(?:^|\s)(?:chez|à|at|vers|to)\s+([^\d\s].*?)\s*(?=\s(?:le|du|on|avec|with|par|via|pour|for)\s|[.,;:!]\s|[.,;:!(]?$|\(|\s\d)""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
)

/** Renvoie null si aucun montant n'est trouvé : la notification est alors ignorée (on ne devine pas). */
fun parsePayment(title: String?, text: String?, pkg: String): Payment? {
    val t = title.orEmpty().trim()
    val all = listOf(t, text.orEmpty().trim()).filter { it.isNotEmpty() }.joinToString("\n")
    // Le premier montant du texte est le paiement ; un solde éventuel vient après.
    val m = listOfNotNull(AMOUNT_AFTER.find(all), AMOUNT_BEFORE.find(all)).minByOrNull { it.range.first } ?: return null
    val amount = (m.groupValues[1].filter(Char::isDigit) + "." + m.groupValues[2].ifEmpty { "0" }).toDouble()
    if (amount <= 0) return null

    val merchant = MERCHANT.find(all)?.groupValues?.get(1)?.trim()
        ?: t.takeIf { pkg in TITLE_IS_MERCHANT && it.none(Char::isDigit) }
        ?: ""
    return Payment(amount, merchant.take(60))
}
