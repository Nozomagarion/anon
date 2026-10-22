# ANON pour iPhone et iPad

Portage natif de l’application Android, en français, pour **iOS 16 et supérieur**. SwiftUI, Vision, Core Image, AVFoundation et PhotoKit ; aucune dépendance externe dans l’application et aucun service de traitement distant. L’atlas des quatre portraits fictifs pèse environ 95 Ko. La taille du binaire n’a pas encore été mesurée.

**État au 21 octobre 2026 :** sources et projet Xcode créés. Les 7 tests du code commun ont été compilés et exécutés avec Swift 6.0.3 sous Linux/WSL. La syntaxe des sources iOS et la structure du projet ont été vérifiées. **L’application n’a pas encore été compilée avec le SDK Apple, ni exécutée sur simulateur ou iPhone** : ce poste est sous Windows et aucun Mac n’est connecté. Le dossier contient un projet à compiler, pas un IPA signé prêt à installer.

## Ouvrir et installer sur un iPhone

1. Copier tout ce dossier `ios` sur un Mac équipé de Xcode et de son SDK iOS. Le projet est au format Xcode 14 compatible Xcode 16+, avec le langage Swift 5.
2. Ouvrir **`ANON.xcodeproj`**. Aucun générateur de projet, CocoaPods ni téléchargement de modèle n’est nécessaire.
3. Choisir la cible **ANON**, puis **Signing & Capabilities**. Choisir son équipe Apple dans **Team** et conserver la signature automatique. Adapter `fr.anon.app.ios` si ce Bundle ID est déjà pris dans le compte.
4. Brancher l’iPhone, accepter la connexion et activer le mode développeur si iOS le demande. Choisir cet iPhone comme destination puis **Run**.
5. Avant de diffuser l’app, exécuter les tests ci-dessous et vérifier des exports sur l’iPhone réel.

La signature et l’installation passent par le compte configuré dans Xcode. Voir [les instructions Apple pour lancer une app sur un appareil](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices).

## Utilisation

- Importer depuis le sélecteur Photos ou depuis Fichiers. Le sélecteur système n’accorde à ANON que l’accès au média choisi ; il peut télécharger un original stocké dans iCloud.
- **Photo :** toucher les cadres pour masquer ou conserver un visage ; utiliser **Tous**, **Aucun** et **+ Zone** pour ajouter un rectangle à la main.
- **Vidéo :** tous les visages détectés sont traités automatiquement. L’aperçu avant export montre la **première image**. La vidéo finale devient lisible dans l’éditeur après export.
- Régler le flou ou changer les portraits fictifs, puis exporter. **Enregistrer** ajoute le résultat à Photos ; **Partager** permet de l’envoyer ou de l’enregistrer dans Fichiers.
- Garder ANON ouvert pendant l’export. Le bouton **Annuler** ferme l’encodeur et supprime le fichier incomplet. Le passage en arrière-plan annule le traitement en cours.

Le résultat final est conservé temporairement pour le partage. Il faut l’enregistrer ou le partager avant de changer de média, de modifier les réglages ou de redémarrer l’application. L’original dans Photos/Fichiers n’est jamais modifié.

## Traitement

Vision détecte les rectangles sans identifier les personnes. La détection travaille à 960 pixels maximum sur le grand côté, avec quatre recadrages chevauchants pour les images suffisamment grandes. Chaque image vidéo décodée passe dans le détecteur ; le lecteur fournit la suivante lorsque l’encodeur peut la recevoir. Un suivi géométrique conserve les masques pendant 200 ms et couvre le déplacement entre deux positions.

Le rendu applique une marge de 20 % et un portrait fictif opaque. Le flou est calculé **uniquement sur les portraits fictifs** avant leur application ; aucun mélange avec les pixels du visage original n’est prévu dans la zone couverte. Les photos sont décodées avec leur orientation, limitées à 4096 pixels sur le grand côté et exportées en PNG sRGB. Les vidéos sont réencodées en H.264/MP4, orientées dans leurs pixels, avec audio AAC stéréo 48 kHz. Les dimensions vidéo sont conservées, arrondies au pixel pair supérieur si nécessaire.

Il s’agit du même principe de remplacement que dans ANON Android, pas du moteur propriétaire du plugin Baskl. Les portraits sont des textures fixes, sans modèle de face swap neuronal. Les sorties iOS et Android ne sont pas identiques au pixel près : détecteurs et filtres natifs diffèrent.

## Métadonnées

Le nettoyage est obligatoire avant de proposer un fichier à Photos ou au partage. Un échec annule l’export.

- Le PNG est créé à partir des pixels rendus ; seuls IHDR, PLTE, IDAT, IEND et tRNS sont conservés. EXIF, GPS, XMP, texte, profils et éventuelles données après IEND sont supprimés.
- Le MP4 est écrit sans métadonnées source ni piste de métadonnées. Les blocs descriptifs, noms de gestionnaire et dates absolues sont effacés après fermeture de l’encodeur. La langue devient `und`. Les données indispensables à la lecture, au son et au minutage restent présentes.
- Les noms de fichiers utilisent un UUID, sans nom ni date du média d’origine. Les copies de travail sont dans le répertoire temporaire privé et sont purgées au lancement suivant.

iOS et Photos attribuent leurs propres dates au **nouvel export**. Elles ne correspondent pas aux dates de prise de vue supprimées. Ce mécanisme ne nettoie pas rétroactivement des fichiers déjà exportés.

## Vérifier sur Mac

Depuis ce dossier :

```sh
bash scripts/verify-macos.sh
```

Le script compile le code commun, construit l’application iOS sans signature, puis lance les tests sur un simulateur iPhone disponible. Pour choisir un simulateur précis :

```sh
ANON_SIMULATOR_ID=IDENTIFIANT_DU_SIMULATEUR bash scripts/verify-macos.sh
```

Les tests iOS incluent le remplacement opaque, une vidéo synthétique avec son et rotation, la conservation des images, la suppression des métadonnées et l’annulation. Ils restent à exécuter sur Mac. La vidéo de test n’utilise que les portraits fictifs ; les vidéos personnelles ne sont pas incluses dans le projet.

Pour les tests portables uniquement :

```sh
swift test --package-path ANONCore
```

Une fois le compte de développement et les appareils provisionnés, `bash scripts/archive-ios.sh TEAM_ID` produit une archive et tente un export IPA de développement dans `build/Export`. Pour une installation personnelle, le bouton Run de Xcode reste le chemin le plus direct.

## Limites de cette version

Les vidéos HDR sont refusées avec un message explicite ; les convertir en SDR avant import. La prévisualisation vidéo avant export est une image fixe. Le débit d’export, la mémoire et le comportement du rendu doivent encore être mesurés sur un iPhone réel. Aucun test de rappel exhaustif du détecteur n’a été réalisé : des petits visages, profils, masques ou scènes floues peuvent être manqués. La voix, les vêtements et le contexte restent présents. Le remplacement protège les zones effectivement couvertes ; il ne garantit pas l’anonymat de toute la scène.

## Fichiers

- `ANON/` : interface, import, traitement et export iOS.
- `ANONCore/` : géométrie, suivi et nettoyage des métadonnées, partagés entre le projet Xcode et les tests Swift Package.
- `ANONTests/` : tests nécessitant les frameworks Apple et fixture synthétique.
- `scripts/make-project.py` : régénération facultative du projet Xcode (Python standard seulement).
- `scripts/prepare-assets.py` : régénération facultative des assets depuis le dépôt Android ; nécessite Pillow et FFmpeg. Les assets sont déjà fournis.

Références d’API : [Vision](https://developer.apple.com/documentation/vision/vndetectfacerectanglesrequest), [AVAssetReader](https://developer.apple.com/documentation/avfoundation/avassetreadertrackoutput), [AVAssetWriter](https://developer.apple.com/documentation/avfoundation/avassetwriter), [Image I/O](https://developer.apple.com/documentation/imageio/cgimagedestination).
