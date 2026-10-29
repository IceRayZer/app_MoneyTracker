# MoneyTracker — plan de travail

Document de reprise pour une nouvelle session Claude Code. Lis-le en entier avant de toucher au code.

## 1. Contexte et contraintes

- L'utilisateur gère ses finances perso. L'app doit rester **strictement locale** : aucune donnée envoyée à un serveur, aucun compte, aucun mot de passe, aucun identifiant bancaire.
- Appareil : **Android uniquement** (pas d'iPhone).
- Objectif : une app simple, rapide, belle, avec saisie texte et vocale, comptes, entrées mensuelles, retraits, épargne, sauvegarde JSON, et **détection automatique des paiements** via les notifications (Google Wallet, Caisse d'Épargne et autres banques).
- Les mises à jour doivent se faire **par-dessus** l'app installée, sans perte de données.

## 2. Ce qui existe déjà

Une app web mono-fichier, `moneytracker-mobile.html` (copie dans le dossier de travail sous le nom `index.html`, dans le dépôt cloné `/home/user/app_moneytracker`, non commitée). Elle contient :

- Stockage `localStorage` (clé `moneyData`) : `{ accounts, expenses, savings, withdrawals }`.
- Comptes : nom, solde, tag, réordonnables au glisser.
- Entrées mensuelles (dépenses et revenus) liées à un compte, avec tag.
- Retraits ponctuels datés, qui diminuent le solde du compte.
- Dashboard : patrimoine, net mensuel et annuel, par compte.
- Épargne : projections sur 1, 5 et 10 ans, sans intérêts composés.
- Saisie texte et vocale (`SpeechRecognition`, peu fiable) acceptant des phrases du type « loyer 800 sur N26 tag logement ».
- Export et import JSON.
- Un début de gestion des notifications dans la session précédente : `fromNotif(text)`, déclenché par `?notif=...`, pré-remplit un retrait. Non utilisé en production.

Fichiers ajoutés dans le dossier de travail précédent (`/home/user/moneytracker/`, pas dans le dépôt) : `manifest.webmanifest`, `sw.js`, `icon-192.png`, `icon-512.png`. Utiles seulement pour la version web installable.

## 3. Décision technique

**App Android native** qui embarque l'interface web, plus un petit module natif pour les notifications.

- Une seule `Activity` avec une `WebView` qui charge les fichiers depuis `assets/` (pas d'URL distante). Hors ligne par nature.
- Un `NotificationListenerService` (module natif) qui écoute les notifications des apps autorisées, extrait montant et commerçant, et les stocke en attente.
- Un pont JavaScript (`addJavascriptInterface`) pour que l'interface lise les paiements en attente et les valide.
- Le stockage reste `localStorage` de la WebView, donc local à l'app.
- **Pourquoi pas une web app seule :** une page web ne peut pas lire les notifications d'autres apps.
- **Pourquoi pas de code distant :** l'app aurait accès aux notifications, donc elle ne doit charger que du code embarqué et audité.

Garder minimal : pas de framework (ni Capacitor, ni React), Kotlin pur, une seule activité, pas de bibliothèque hors AndroidX de base.

## 4. Mises à jour sans réinstaller

- **Clé de signature fixe**, générée une fois et stockée dans les secrets GitHub du dépôt (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Sans elle, Android refuse la mise à jour.
- **`versionCode` incrémenté** à chaque release.
- **GitHub Actions** : à chaque tag `v*`, compile l'APK signé et le publie dans une Release.
- L'utilisateur télécharge le nouvel APK et l'installe par-dessus. Les données sont conservées tant que le `applicationId` et la signature ne changent pas.

## 5. Détection des paiements (spécification)

Sources à filtrer par nom de paquet (liste modifiable dans une constante) :
- Google Wallet : `com.google.android.apps.walletnfcrel`
- Caisse d'Épargne et autres banques : **à confirmer avec l'utilisateur** (noms de paquets à relever sur son téléphone).

Extraction, à partir de `title` + `text` de la notification :
- **Montant** : nombre suivi de `€` ou `EUR`, ou précédé de `€`. Gérer `12,50`, `1 234,00`, `€8.40`.
- **Commerçant / bénéficiaire** : texte après « chez », « à », « at », « vers », « to ». Peut être vide : l'utilisateur complète.
- **Doublons** : une même notification peut arriver deux fois. Déduplication sur (paquet + texte + minute).

Règles :
- Rien n'est ajouté automatiquement au solde. Chaque paiement détecté apparaît dans une file « à valider » : l'utilisateur choisit le compte, confirme ou rejette.
- Si le montant n'est pas trouvé, la notification est ignorée (ne rien deviner).
- Les notifications brutes ne sont **jamais** stockées ni envoyées. Seul le résultat validé entre dans `localStorage`.

Test de l'analyse : fonction pure, testée avec des phrases d'exemple (le plus simple : un test JS ou un test JUnit sur la fonction d'extraction).

## 6. Étapes

1. **Interface web** (`app/src/main/assets/index.html`) : refaire le design (mobile d'abord, contrastes conformes WCAG AA, zones tactiles d'au moins 44 px, pas de dépendance externe). Garder toutes les fonctionnalités listées au §2. Ajouter la file « paiements à valider ».
2. **Projet Android** : Gradle Kotlin DSL, `minSdk` 26, `targetSdk` récent, un module `app`. Une `MainActivity` (WebView), un `PaymentListenerService`, un `Bridge` JavaScript.
3. **Extraction** : fonction pure `parsePayment(title, text, pkg)`, puis un test.
4. **Pont** : `Bridge.pendingPayments()` renvoie le JSON des paiements en attente ; `Bridge.ack(id)` les supprime.
5. **Workflow GitHub Actions** : compilation sur tag `v*`, signature, publication de la Release.
6. **Version web de secours** (facultatif) : GitHub Pages, sans détection automatique, pour tester l'interface depuis un navigateur.
7. **Guide d'installation** dans `README.md` : activer « Sources inconnues », puis accorder l'accès aux notifications dans les réglages.

## 7. Blocages connus (à lever avant de coder ou de compiler)

- **Push GitHub refusé.** Le dépôt `IceRayZer/app_moneytracker` est attaché en lecture, mais Claude n'a pas accès en écriture. Solution : installer l'app GitHub Claude sur le compte ou le dépôt (https://github.com/apps/claude/installations/select_target), ou reconnecter GitHub depuis claude.ai (https://claude.ai/customize/connectors?auth_start=github&auth_start_force=1).
- **Compilation impossible ici.** Le réseau de l'environnement bloque `dl.google.com` (Android SDK). Solution : dans l'environnement cloud de la session (menu de session, Edit, Network access), autoriser `dl.google.com` en gardant « Allow package managers ». Sans cela, pas de compilation locale : le CI GitHub reste possible si le SDK y est installé.
- **Pas d'émulateur** (pas de `/dev/kvm`). L'affichage et la vraie écoute des notifications ne peuvent être testés que par l'utilisateur sur son téléphone.

## 8. Ce que l'utilisateur doit fournir

- Confirmation du plan (« go »).
- Les noms de paquets de ses apps bancaires (Caisse d'Épargne, autres).
- Des exemples de notifications réelles, avec les données personnelles masquées, pour caler l'extraction.
- Le test sur son téléphone : installer l'APK, accorder l'accès aux notifications, dire ce qui ne marche pas.

## 9. Critères de réussite

- L'APK se compile sans erreur (CI ou local).
- Les tests d'extraction passent sur les exemples fournis.
- L'app s'installe, se met à jour sans perte de données, et fonctionne hors ligne.
- Aucune requête réseau sortante de l'app (à vérifier dans le manifeste : pas de permission `INTERNET` si elle n'en a pas besoin).
- Aucune donnée bancaire ni notification brute n'est persistée.

## 10. Règles de travail

- Code minimal, sans bibliothèque superflue. Tout choix simplifié doit être signalé en commentaire avec son plafond.
- Ne jamais inventer un résultat de test : si quelque chose n'a pas tourné, le dire.
- Ne rien pousser sans que l'accès GitHub soit confirmé.

## 11. État (29/10/2026)

- Étapes 1 à 7 faites : interface refaite (`app/src/main/assets/index.html`, axe-core 0 violation WCAG 2.2 AA), projet Android Kotlin, `parsePayment` + 18 tests JUnit, pont JS, workflows Android (tests + APK release non signé à chaque push, APK signé + Release sur tag `v*`) et Pages, `README.md`.
- Push GitHub : fonctionne. Compilation : faite par GitHub Actions (le SDK reste bloqué dans l'environnement Claude).
- Apps surveillées : Google Wallet (+ services Google Play), Caisse d'Épargne (`com.caisseepargne.android.mobilebanking`), N26. À compléter selon les banques de l'utilisateur.
- Reste à faire par l'utilisateur : secrets de signature, premier tag, test sur le téléphone, exemples réels de notifications pour affiner l'extraction.

## 12. Synchro entre téléphones (30/10/2026)

- Bluetooth classique (RFCOMM), appareils déjà jumelés dans Android : le lien est chiffré et authentifié par le jumelage. Aucune permission Internet. NFC écarté (trop lent, téléphones à garder collés).
- Le natif ne fait que transporter (`BluetoothSync.kt`, trames dans `Frames.kt`, pont dans `Bridge.kt`) ; la fusion est en JavaScript (`mergeData` dans `index.html`) : élément le plus récent gagne (`updatedAt`), suppressions par « pierres tombales » (`data.deleted`), comptes de même nom unifiés.
- Données reçues revalidées comme un import (`cleanBackup`). L'hôte doit accepter la demande avant tout envoi.
- Doublons de paiements : à la synchro, les paiements en attente déjà validés sur l'autre téléphone (même montant, heure de notification à 90 s près, champ `nt`) sont retirés de « À valider ».
- Testé ici : fusion (8 scénarios, convergence des deux sens), trames (5 tests), parcours à l'écran avec deux téléphones simulés. **Pas testé : le vrai Bluetooth entre deux téléphones** (pas de matériel ici).
