# ANON — documentation Android

Application Android native, en français, pour remplacer les visages par des portraits fictifs puis flouter ces portraits. Traitement entièrement local, modèle de détection inclus et aucune permission Internet.

**APK prêt à installer :** `output/ANON-0.1.1-android-arm64.apk` (environ 16 Mo, Android 10+ ARM64). Voir le [rapport de validation](VALIDATION.md) et les exemples dans `output/tests/`.

## Utilisation

1. Installer l’APK sur Android 10 ou supérieur (ARM64 pour la version compacte).
2. Importer une photo ou une vidéo avec le sélecteur Android.
3. **Photo** : toucher les cadres ou les boutons « Visage » pour sélectionner les visages à anonymiser. « Tous », « Aucun » et « + Zone » permettent de corriger la sélection ou dessiner une zone oubliée.
4. **Vidéo** : tous les visages détectés sont traités automatiquement, sans sélection individuelle. L’aperçu se lance avec le bouton lecture du lecteur.
5. Régler le flou, éventuellement changer les faux visages, puis exporter dans la galerie. Les fichiers apparaissent dans `Pictures/ANON` ou `Movies/ANON`. Les vidéos gardent leur audio.

Le média original n’est jamais modifié. Garder l’application ouverte pendant un export vidéo ; il est annulable.

Depuis la version 0.1.1, tous les nouveaux exports sont nettoyés automatiquement : EXIF, GPS, XMP, auteur, appareil, commentaires, titres et dates intégrées sont exclus. Les PNG sont réencodés en sRGB et ne gardent que les blocs nécessaires aux pixels et à la transparence. Pour les MP4, les métadonnées sont bloquées au muxage, les données descriptives et dates absolues du conteneur sont effacées, et la langue de piste devient indéterminée. Les codecs, dimensions, orientation et durées restent présents pour permettre la lecture. Le nom de sortie est aléatoire et ne reprend ni le nom source ni une date.

Android conserve ses propres dates de création du nouveau fichier dans le système de fichiers et la galerie. Ce ne sont pas les dates de prise de vue du média d’origine. Les anciens exports ne sont pas nettoyés rétroactivement : les refaire avec la version mise à jour.

## Ce qui est réellement implémenté

- Kotlin et vues Android natives, sans WebView ni serveur.
- ML Kit Face Detection embarqué : détection et suivi, sans reconnaissance de personnes.
- Quatre visages fictifs créés à la fabrication de l’application, utilisés comme textures opaques. Pas de génération en ligne et pas de face swap neuronal adaptant la pose.
- Le flou est calculé sur ces textures uniquement. Aucun pixel du visage d’origine n’est mélangé à l’intérieur du rectangle de remplacement. Une marge de 20 % autour du visage agrandit la zone couverte.
- Sur photo, l’export PNG est aplati et ne reprend pas les métadonnées EXIF d’origine. Les très grandes photos sont réduites à 4096 pixels sur le grand côté, dimension affichée dans l’éditeur.
- Sur vidéo, Media3 décode, traite chaque image décodée et réencode en H.264/MP4 ; l’audio est conservé ou réencodé en AAC. La détection précise travaille sur une image de 960 pixels maximum sur le grand côté et quatre recadrages qui se chevauchent pour mieux repérer les visages dans une foule ; le rendu garde la résolution vidéo. Une union des positions successives et une rétention de 200 ms limitent les trous entre détections. Le suivi associe les rectangles géométriquement ; il ne reconnaît pas l’identité des personnes.
- L’aperçu vidéo utilise la détection globale et réutilise les positions pendant au plus 80 ms pour alléger la lecture. L’export refait la détection avec les recadrages à chaque image. La vitesse d’export dépend du téléphone ; le temps réel n’est pas garanti.
- Si le détecteur ou le rendu échoue, l’export échoue, sans publier de fichier vidéo incomplet dans la galerie.
- L’export limite le décodeur à une image en attente pour éviter l’écrasement d’images par la surface pendant l’inférence. Ce choix privilégie la conservation des images au débit maximal.

## Limites à connaître

Le principe **remplacement opaque → flou → export aplati** empêche de retrouver les pixels originaux dans les zones effectivement remplacées. Il ne garantit pas l’anonymat d’une personne : la détection peut manquer un petit visage, un profil, un visage masqué ou flou ; les vêtements, la voix, les cheveux et le contexte restent visibles/audibles. Vérifier le résultat avant diffusion. Il n’existe pas ici de garantie « jamais défloutable » sur l’ensemble du média.

Les textures sont frontales et posées dans des rectangles. Le résultat n’est donc pas une reproduction du moteur propriétaire de Baskl : il reprend le principe demandé sans prétendre utiliser son code, ses modèles ou son algorithme. Les vidéos HDR ne sont pas prises en charge par le moteur de remplacement actuel. Pas de traitement en service de premier plan ni de reprise après arrêt forcé du processus.

## Construction

JDK 17, Android SDK avec la plateforme 36 ; Gradle 8.13 est fourni via le wrapper.

Créer `local.properties` avec `sdk.dir=CHEMIN_DU_SDK`, puis :

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease
```

- `app/build/outputs/apk/debug/app-debug.apk` : APK de développement universel, émulateurs inclus.
- `app/build/outputs/apk/release/app-release.apk` : APK ARM64 compact, optimisé par R8, **signé avec la clé de développement** pour installation directe. Configurer une clé privée dédiée avant toute publication en magasin.

La configuration locale du SDK et les vidéos utilisateur ne sont pas nécessaires à la compilation. Les vidéos dans `video/` servent de fixtures locales et ne sont pas embarquées dans l’APK.

## Vérification

Les tests unitaires contrôlent les limites des zones et leur union pendant un mouvement. Les tests instrumentés vérifient :

- le fonctionnement du détecteur embarqué ;
- l’indépendance du résultat vis-à-vis des pixels source à l’intérieur d’une zone remplacée, et la conservation de l’arrière-plan ;
- un export vidéo réel : chaque image d’entrée passe dans le moteur, les masques correspondent aux textures fictives attendues, l’audio et la durée sont préservés. Ces contrôles ne mesurent pas le rappel du détecteur sur tous les visages humains de la scène : les vidéos n’ont pas d’annotations exhaustives de référence.

Pour le test vidéo, copier un clip contenant des visages vers `/sdcard/Android/data/fr.anon.app/files/test-input.mp4`, puis lancer les tests instrumentés. `scripts/test-videos.ps1` automatise les essais sur les vidéos locales et récupère les sorties sans toucher aux originaux.

Voir [les assets et le prompt de génération](ASSETS.md).
