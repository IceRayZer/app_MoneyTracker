# MoneyTracker

Suivi de mes comptes sur Android, sans rien saisir : l'app lit les notifications de paiement (Google Wallet, Caisse d'Épargne, N26…) et me propose chaque dépense à valider en un geste.

- **100 % local** : pas de compte, pas de serveur, pas de permission Internet. Les données restent dans le téléphone.
- Rien n'est ajouté sans validation : chaque paiement détecté arrive dans « À valider » sur l'accueil, tu choisis le compte et tu valides (ou tu ignores).
- Le texte des notifications n'est jamais enregistré, seuls le montant et le commerçant le sont.

## Installer sur le téléphone

1. Sur le téléphone, ouvre la page **Releases** du dépôt et télécharge le dernier `MoneyTracker-vX.Y.Z.apk`.
2. Ouvre le fichier. Android demande d'autoriser l'installation depuis ton navigateur : **Paramètres → Autoriser cette source**, puis **Installer**.
3. Ouvre MoneyTracker, puis **Plus → Détection des paiements → Ouvrir le réglage** et active **MoneyTracker**.
   - Android 13 et plus : si l'interrupteur est grisé (« paramètre restreint »), va dans **Paramètres → Applications → MoneyTracker → menu ⋮ → Autoriser les paramètres restreints**, puis recommence.
4. Ajoute tes comptes (onglet **Comptes**). Au prochain paiement, il apparaît sur l'accueil.

## Mettre à jour

Télécharge le nouvel APK depuis Releases et installe-le **par-dessus** : les données sont conservées (même identifiant d'app, même signature).

## Récupérer les données de l'ancienne version web

L'app Android a son propre stockage. Dans l'ancienne version (navigateur) : ⚙️ → **Exporter**. Dans l'app : **Plus → Importer un fichier**.

## Ajouter une banque

Les apps surveillées sont dans `PAYMENT_APPS` (`app/src/main/java/com/icerayzer/moneytracker/PaymentParser.kt`). Le nom de paquet d'une app se trouve dans son adresse Play Store (`...details?id=nom.du.paquet`).

## Publier une version (mainteneur)

Une fois pour toutes, dans **Settings → Secrets and variables → Actions** du dépôt, créer les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (clé de signature). Garder une copie de la clé : sans elle, plus aucune mise à jour ne pourra s'installer par-dessus.

Ensuite, pour chaque version : créer un tag `vX.Y.Z` (ex. depuis **Releases → Draft a new release → Choose a tag**). GitHub Actions compile, teste, signe l'APK et le publie dans la Release.

## Version web (facultatif)

`app/src/main/assets/` fonctionne aussi dans un navigateur, sans la détection des paiements. Pour la publier : **Settings → Pages → Source : GitHub Actions**, puis lancer le workflow **Web**.

## Développement

- Interface : `app/src/main/assets/index.html` (un seul fichier, sans dépendance).
- Natif : `MainActivity` (WebView), `PaymentListenerService` (notifications), `Bridge` (pont JavaScript), `parsePayment` (extraction, testée dans `PaymentParserTest`).
- `./gradlew test assembleDebug`
