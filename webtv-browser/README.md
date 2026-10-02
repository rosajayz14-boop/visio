# WebTV — navigateur vidéo pour Google TV Streamer 4K & Fire TV

Navigateur web **plein écran, piloté à la télécommande**, pensé pour regarder des sites
vidéo sur une TV Android (Google TV, Fire TV, Android TV). Un seul APK pour tous.

## Fonctions

- **Curseur à la télécommande** : la croix déplace un pointeur, **OK** clique. Il se cache
  tout seul après 3 s et revient au premier appui. Désactivable (« Curseur: OFF »).
- **Favoris** : « ☆ Ajouter » enregistre la page, « ★ Favoris » ouvre la liste (OK ouvre,
  « Supprimer » enlève). La page d'accueil affiche tes favoris en tuiles avec leur icône.
- **Anti-pub** par liste de domaines (`app/src/main/assets/adblock_hosts.txt`), désactivable.
- **Popups** : bloqués par défaut (les pubs au clic sur « Play » disparaissent, la vidéo se
  lance en place) ; les vrais liens « nouvel onglet » s'ouvrent quand même dans la page.
  « Popups: ON » ouvre les popups dans une fenêtre séparée ; **Retour** revient à la page.
- **Vue : Auto / PC / Mobile** : change le User-Agent. « Auto » (défaut) passe Cloudflare et
  les connexions ; « PC » / « Mobile » dépannent un lecteur capricieux.
- **Zoom texte A− / A+** pour la lisibilité des sites « PC » sur TV (mémorisé).
- **Vidéo en plein écran** : **◄ ►** reculent / avancent de 10 s, **OK** lecture/pause, avec
  un indicateur à l'écran ; **Retour** quitte le plein écran. Fonctionne aussi quand le
  lecteur est dans une iframe d'un autre domaine (script injecté dans chaque frame).
  Si aucune vidéo n'est détectée, les touches vont au lecteur du site tel quel.
- **Mises à jour automatiques** : au lancement, l'app propose la nouvelle version en un clic
  (« Installer », « Plus tard », « Ignorer cette version »).
- Les réglages (Vue, Popups, Anti-pub, Curseur, Zoom) sont **mémorisés**.

## Télécommande

| Geste | Effet |
|---|---|
| **OK long** (≈ ½ s) — ou **MENU** sur Fire TV | Ouvre / ferme la barre d'outils (dans tous les modes) |
| Croix | Déplace le curseur (ou navigation native si Curseur: OFF) |
| OK court | Clic |
| Retour | Page précédente (coupe le son de la vidéo) · ferme popup / plein écran · ×2 : quitter |
| En plein écran : ◄ ► / OK | −10 s / +10 s / lecture-pause |

## Ce que l'app ne peut pas faire

- **Netflix, Disney+, Prime Video, Canal+…** : DRM (Widevine L1) → ne jouent pas dans une
  WebView. Utilise leurs applications.
- Le blocage de pubs est par **domaine** : très efficace sur les sites de streaming gratuits,
  mais pas de filtrage cosmétique.

## Obtenir / mettre à jour l'APK

Le dépôt compile automatiquement à chaque push (GitHub Actions). Pour une version installable :

1. Mets à jour `versionName` (et `versionCode`) dans `app/build.gradle`.
2. Crée une **Release** GitHub avec le tag **`v<versionName>`** (ex. `v3.3`). Le workflow
   vérifie que le tag correspond, compile et attache `app-debug.apk`.
3. Lien direct : `https://github.com/<user>/<repo>/releases/download/v3.3/app-debug.apk`
   (utilisable dans **Downloader** ou via un code **aftv.news**).
4. Ensuite, l'app installée se met à jour **toute seule** au lancement.

### Signature stable (important)

L'APK est signé avec `app/debug.keystore`, **versionné dans le dépôt**. Toutes les versions
ont donc la même signature et s'installent **par-dessus** la précédente sans désinstaller.
(Avant cela, chaque build CI générait une clé aléatoire → « Application non installée ».)
Le premier passage vers une version à clé fixe demande **une** désinstallation de l'ancienne.

## Structure

```
app/src/main/java/com/jason/webtv/
  MainActivity.java   UI, télécommande, curseur, popups, plein écran, contrôle vidéo, updater
  Favorites.java      favoris (SharedPreferences/JSON)
  Updater.java        vérification + téléchargement des Releases GitHub
  AdBlocker.java      liste de domaines bloqués
app/src/main/assets/home.html     page d'accueil (tuiles de favoris)
app/src/main/assets/adblock_hosts.txt
app/debug.keystore                clé de signature fixe
.github/workflows/build.yml       build + Release
```
