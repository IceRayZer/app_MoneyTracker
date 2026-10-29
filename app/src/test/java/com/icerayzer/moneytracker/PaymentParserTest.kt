package com.icerayzer.moneytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentParserTest {
    private val wallet = "com.google.android.apps.walletnfcrel"
    private val bank = "com.caisseepargne.android.mobilebanking"

    private fun check(title: String, text: String, pkg: String, amount: Double, merchant: String) {
        val p = parsePayment(title, text, pkg)
        assertEquals("montant de « $title / $text »", amount, p!!.amount, 0.001)
        assertEquals("commerçant de « $title / $text »", merchant, p.merchant)
    }

    @Test fun virguleEtChez() = check("Paiement accepté", "Paiement de 12,50 € chez Carrefour le 12/03", bank, 12.50, "Carrefour")
    @Test fun milliersAvecEspace() = check("", "Achat CB 1 234,00 EUR à DARTY PARIS", bank, 1234.00, "DARTY PARIS")
    @Test fun milliersAvecPoint() = check("", "Virement de 1.250,00 € vers Jean Dupont.", bank, 1250.00, "Jean Dupont")
    @Test fun espaceFineInsecable() = check("", "45,99 € chez Amazon.fr", bank, 45.99, "Amazon.fr")
    @Test fun euroDevant() = check("", "€8.40 at Starbucks", wallet, 8.40, "Starbucks")
    @Test fun anglaisTo() = check("", "You paid €5 to John", wallet, 5.0, "John")
    @Test fun eurEnLettres() = check("", "Paiement 12.5 EUR", bank, 12.5, "")
    @Test fun walletTitreCommercant() = check("Boulangerie Paul", "8,40 € avec Visa •••• 1234", wallet, 8.40, "Boulangerie Paul")
    @Test fun banqueTitreIgnore() = check("Caisse d'Epargne", "Paiement carte 23,10 €", bank, 23.10, "")
    @Test fun aSuiviDuneHeure() = check("", "Paiement de 9 € à 14h32", bank, 9.0, "")
    @Test fun montantDansLeTitre() = check("12,00 €", "Chez Lidl", wallet, 12.0, "Lidl")
    @Test fun numeroDeCarteNonPrisPourMontant() = check("", "Carte ••1234 : 19,90 € chez Fnac", bank, 19.90, "Fnac")

    @Test fun dateAvantMontant() = check("", "Paiement le 12/03 250,00 € chez Leclerc", bank, 250.0, "Leclerc")
    @Test fun heureAvantMontant() = check("", "Achat à 14:32 100 € chez Fnac", bank, 100.0, "Fnac")
    @Test fun premierMontantPasLeSolde() = check("", "You paid €8.40 at Starbucks. Balance 120,00 €", wallet, 8.40, "Starbucks")

    @Test fun sansMontantIgnore() = assertNull(parsePayment("Google Wallet", "Votre carte a été ajoutée", wallet))
    @Test fun nombreSansDeviseIgnore() = assertNull(parsePayment("Code", "Votre code est 123456", bank))
    @Test fun texteNull() = assertNull(parsePayment(null, null, bank))
}
