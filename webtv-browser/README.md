# WebTV — navigateur sans pub pour Fire TV & Google TV Streamer 4K

Application Android TV : un **navigateur web plein écran** avec **bloqueur de publicités intégré**
et un **mode curseur** pilotable à la télécommande. Un seul APK fonctionne sur **Amazon Fire TV /
Fire Stick** et sur le **Google TV Streamer 4K** (et tout appareil Android TV).

---

## Ce que l'app fait

- Ouvre n'importe quel site web sur ta TV (barre d'adresse + recherche Google).
- **Bloque les pubs et traceurs** via une liste de ~150 domaines (régies pub, pré-rolls vidéo,
  pop-ups des sites de streaming gratuits). Modifiable dans `app/src/main/assets/adblock_hosts.txt`.
- **Mode curseur** : la croix de la télécommande déplace un pointeur, le bouton OK clique — indispensable
  pour naviguer sur des sites non pensés pour la TV.
- **Lecture vidéo HTML5 en plein écran** (autoplay activé).
- Garde l'écran allumé pendant la lecture.

## Ce que l'app ne peut PAS faire (limite technique de WebView, pas un choix)

- **Netflix, Disney+, Amazon Prime Video** : protégés par DRM (Widevine L1) — ils ne jouent pas dans un
  navigateur WebView. Utilise leurs applications officielles.
- **Pubs YouTube** : servies depuis le même domaine que les vidéos, donc non bloquables par liste de
  domaines. Pour YouTube sans pub, il faut l'app YouTube + un abonnement, ou une app dédiée.
- Le blocage est basé sur les **domaines** : il enlève la grande majorité des pubs et pop-ups, mais pas
  100 % sur tous les sites (pas de filtrage cosmétique CSS).

---

## Obtenir l'APK — 3 méthodes

### ✅ Méthode 1 (recommandée) — GitHub Actions : **zéro logiciel à installer**

1. Crée un compte gratuit sur https://github.com (si tu n'en as pas).
2. Crée un nouveau dépôt (bouton **New repository**), par ex. `webtv`, en **Public** ou **Private**.
3. Envoie ce projet dans le dépôt. Depuis un terminal, dans le dossier du projet :
   ```bash
   git init
   git add .
   git commit -m "WebTV v1.0"
   git branch -M main
   git remote add origin https://github.com/TON_UTILISATEUR/webtv.git
   git push -u origin main
   ```
   *(Ou glisse-dépose les fichiers via le bouton « Add file » → « Upload files » sur github.com.)*
4. Va dans l'onglet **Actions** du dépôt : le build « Build APK WebTV » démarre tout seul.
   Attends ~3-5 minutes qu'il passe au vert ✅.
5. Clique sur le build terminé → section **Artifacts** → télécharge **WebTV-debug-apk**
   (un `.zip` contenant `app-debug.apk`).

**Astuce pour installer directement sur la TV** : crée plutôt une *Release*. Fais
`git tag v1.0 && git push origin v1.0`. GitHub compile et publie automatiquement l'APK dans
**Releases**, avec une **URL publique** que tu peux coller dans l'app **Downloader** de la TV (voir plus bas).

### Méthode 2 — Android Studio (si tu l'as déjà)

1. Ouvre le dossier du projet dans **Android Studio**.
2. Laisse-le télécharger le SDK et synchroniser Gradle.
3. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. L'APK est dans `app/build/outputs/apk/debug/app-debug.apk`.

### Méthode 3 — Ligne de commande (Mac / PC / Linux)

Pré-requis : **JDK 17** et le **SDK Android** (via Android Studio ou les *command-line tools*).
Crée un fichier `local.properties` à la racine avec le chemin du SDK :
```
sdk.dir=/Users/toi/Library/Android/sdk        # Mac
# sdk.dir=C:\\Users\\toi\\AppData\\Local\\Android\\Sdk   # Windows
```
Puis :
```bash
./gradlew assembleDebug          # (gradlew.bat sur Windows)
```
APK généré dans `app/build/outputs/apk/debug/app-debug.apk`.

> L'APK est signé avec la clé *debug* : parfait pour l'installer soi-même (sideload). Pas besoin du Play Store.

---

## Installer l'APK sur Fire TV / Fire Stick

1. **Réglages → My Fire TV → Options pour les développeurs** : active
   **Applications de sources inconnues** (Apps from Unknown Sources).
2. Installe l'app **Downloader** (depuis l'Appstore Amazon).
3. **Option A (URL publique)** : dans Downloader, entre l'URL de l'APK de ta *Release* GitHub
   → il télécharge et propose d'installer.
   **Option B (ADB, depuis ton ordi, même réseau Wi-Fi)** :
   ```bash
   adb connect IP_DE_LA_TV:5555
   adb install app-debug.apk
   ```
   *(L'IP est dans Réglages → My Fire TV → À propos → Réseau.)*
4. L'app **WebTV** apparaît dans « Vos applications et chaînes ».

## Installer l'APK sur Google TV Streamer 4K

1. **Paramètres → Système → À propos** : clique 7 fois sur **Version d'Android** pour activer les
   options développeur, puis **Système → Options pour les développeurs → Débogage USB** (pour ADB).
2. **Paramètres → Applications → Sécurité** : autorise l'installation depuis la source utilisée
   (Downloader, ou le débogage).
3. Même choix qu'au-dessus : app **Downloader** avec l'URL de la Release, **ou** ADB par le réseau :
   ```bash
   adb connect IP_DU_STREAMER:5555
   adb install app-debug.apk
   ```
4. WebTV apparaît dans la liste des applications (utilise « Voir toutes les apps » si besoin).

---

## Utilisation à la télécommande

| Touche | Action |
|--------|--------|
| **MENU** (≡) | Affiche / cache la barre d'adresse |
| **Croix directionnelle** | Déplace le curseur (et fait défiler la page aux bords) |
| **OK / centre** | Clique là où est le curseur |
| **Retour** | Page précédente — puis 2× pour quitter |
| Bouton **Curseur** | Active/désactive le mode curseur |
| Bouton **Anti-pub** | Active/désactive le blocage de pub (recharge la page) |

Au démarrage, la barre d'adresse est ouverte : tape une adresse ou une recherche, puis **OK/Aller**.

---

## Personnaliser le blocage de pub

Édite `app/src/main/assets/adblock_hosts.txt` : un domaine par ligne. Le domaine **et ses sous-domaines**
sont bloqués. Recompile ensuite (méthode 1, 2 ou 3). N'ajoute pas de domaine « à double usage »
(ex. un domaine qui sert aussi à la connexion) sous peine de casser certains sites.

## Détails techniques

- `minSdk 21` (couvre les vieux Fire Stick) · `targetSdk 34` · `compileSdk 34`
- AGP 8.6.1 · Gradle 8.7 · Java 17 · AndroidX (AppCompat + WebKit)
- Compatible lanceur TV via `LEANBACK_LAUNCHER` + bannière `@drawable/banner`

## Note

Le blocage de publicités est un usage légitime et répandu. Utilise ce navigateur pour accéder à des
contenus auxquels tu as droit, et respecte les droits d'auteur et les conditions des sites que tu visites.
