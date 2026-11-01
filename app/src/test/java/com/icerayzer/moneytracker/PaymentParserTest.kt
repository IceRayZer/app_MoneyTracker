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

    // ---- Sens de l'argent, virements, cartes (exemples réels fournis par l'utilisateur en tête) ----
    private fun mv(title: String, text: String, pkg: String = bank) = parsePayment(title, text, pkg)!!

    @Test fun virementCaisseEpargneReel() {
        val p = mv("Votre virement externe a été réalisé",
            "Votre virement instantané de 50,00 EUR a été réalisé le 01/11/2026 à 19h31m15s vers JEAN DUPONT")
        assertEquals(50.0, p.amount, 0.001)
        assertEquals("JEAN DUPONT", p.merchant)
        assertEquals(true, p.transfer)
        assertEquals(false, p.credit)
        assertEquals("EUR", p.currency)
    }

    @Test fun walletReelEnDollars() {
        val p = mv("TRADER JOE S #1234", "\$34.62 with Visa •••• 5678", wallet)
        assertEquals(34.62, p.amount, 0.001)
        assertEquals("TRADER JOE S", p.merchant)
        assertEquals("5678", p.card)
        assertEquals("USD", p.currency)
        assertEquals(false, p.credit)
        assertEquals(false, p.transfer)
    }

    @Test fun walletEnEuros() {
        val p = mv("Carrefour City", "12,40 € avec Mastercard ••••9012", wallet)
        assertEquals("Carrefour City", p.merchant)
        assertEquals("9012", p.card)
        assertEquals("EUR", p.currency)
    }

    @Test fun virementRecu() {
        val p = mv("Virement reçu", "Vous avez reçu un virement de 500,00 € de JEAN DUPONT")
        assertEquals(500.0, p.amount, 0.001)
        assertEquals("JEAN DUPONT", p.merchant)
        assertEquals(true, p.credit)
        assertEquals(true, p.transfer)
    }

    @Test fun argentRecuRevolut() {
        val p = mv("Revolut", "Jean vous a envoyé 20 €", "com.revolut.revolut")
        assertEquals(true, p.credit)
        assertEquals("Jean", p.merchant)
    }

    @Test fun remboursement() {
        val p = mv("", "Remboursement de 15,99 € par Amazon")
        assertEquals(true, p.credit)
        assertEquals("Amazon", p.merchant)
    }

    @Test fun debiteMalgreLeMotRemboursement() = assertEquals(false, mv("", "Votre compte a été débité de 300,00 € (remboursement prêt)").credit)
    @Test fun paiementRefuseIgnore() = assertNull(parsePayment("Paiement refusé", "Paiement de 30,00 € refusé : solde insuffisant", bank))
    @Test fun alerteSoldeIgnoree() = assertNull(parsePayment("Alerte solde", "Le solde de votre compte est de 1 234,56 €", bank))
    @Test fun soldePuisPaiement() = check("", "Solde : 1 234,00 €. Paiement de 12,00 € chez Fnac", bank, 12.0, "Fnac")

    @Test fun memeMouvementVuParDeuxApps() {
        val wallet = Payment(34.62, "TRADER JOE S", card = "5678")
        val n26 = Payment(34.62, "Trader Joe's")
        assertEquals(true, sameMovement(wallet, 0, n26, 90_000))
        assertEquals(false, sameMovement(wallet, 0, n26, 5 * 60_000))                     // trop éloignés
        assertEquals(false, sameMovement(wallet, 0, n26.copy(amount = 34.0), 1_000))       // autre montant
        assertEquals(false, sameMovement(wallet, 0, n26.copy(credit = true), 1_000))       // autre sens
    }
}
