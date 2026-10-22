# Validation — 21 octobre 2026

## Correctif métadonnées — version 0.1.1

- Trois nouveaux tests unitaires : suppression EXIF/XMP/texte PNG, effacement des métadonnées MP4 et dates 32/64 bits sans déplacer les données audio/vidéo, rejet des conteneurs invalides.
- Test photo dans l’activité réelle : JPEG avec auteur, modèle d’appareil, date et coordonnées GPS injectés et vérifiés avant import. L’export PNG n’a plus aucun bloc descriptif, et son nom est un UUID.
- Test vidéo : clip portrait avec coordonnées GPS, titre, auteur, commentaire et date de 2001 injectés. Après export, l’inspection indépendante par ffprobe ne retrouve pas ces champs ; l’audio, les 15 images et la rotation de 90° sont conservés.
- Les nouveaux fichiers n’héritent pas du nom original ni d’une date dans leur nom. Les dates de galerie créées par Android restent celles du nouveau fichier.

## Environnement

- Windows, JDK 17, Gradle 8.13, SDK Android 36.
- Émulateur Android 10 / API 29, x86_64, OpenGL ES 3, rendu hôte NVIDIA. Les tests utilisent l’APK de développement, avec le même moteur que l’APK ARM64 optimisé.
- L’APK livré est une version ARM64 signée avec la clé de développement, destinée à l’installation directe et aux essais. Les contrôles automatisés ci-dessous ont été réalisés sur émulateur ; les performances sur téléphone restent à mesurer.

## Contrôles

- Compilation des APK debug, test et release : réussie.
- Tests unitaires de géométrie : 2 réussis.
- Android Lint : aucune erreur ; avertissements principalement de localisation des textes français et d’API dépréciées.
- Détection embarquée : les quatre portraits fictifs de la fixture sont détectés.
- Remplacement photo : les pixels à l’intérieur d’un visage remplacé sont identiques pour deux images source différentes, tandis que l’arrière-plan reste intact.
- Parcours dans l’activité réelle : import via partage Android, « Aucun », « Tous » et export PNG dans la galerie réussis.
- Signature APK v2 : valide. Manifest final sans permission Internet, sans permission de lecture générale de la galerie et sans permission de stockage général.

## Vidéos locales

Les originaux de `video/` restent intacts. Pour les essais sur émulateur, ils sont copiés avec une réduction de 1920 × 1080 à 960 × 540, en gardant la durée complète et les images. Les résultats et les journaux se trouvent dans `output/tests/`.

Le test compare le nombre d’images analysées au nombre d’images d’entrée, vérifie l’audio et la durée, et compare des pixels exportés aux textures fictives attendues à trois instants. Il ne constitue pas une annotation exhaustive de tous les visages présents dans la foule.

| Source | Images d’entrée | Résultat |
| --- | ---: | --- |
| 5286250-hd_1920_1080_30fps.mp4 | 105 | Réussi, durée du test 75,4 s sur émulateur |
| 5286321-hd_1920_1080_30fps.mp4 | 345 | Réussi, durée du test 244,7 s sur émulateur |
| 5287046-hd_1920_1080_30fps.mp4 | 150 | Réussi, durée du test 107,5 s sur émulateur |

Les trois vidéos complètes passent : **600 images d’entrée traitées**, présence audio et durée conservées. Ces temps mesurent l’ensemble du test sur émulateur, y compris les vérifications après export ; ils ne prédisent pas la vitesse sur téléphone.

Un clip supplémentaire de 15 images, dérivé d’une vidéo fournie et encodé avec une matrice de rotation de 90°, passe aussi le contrôle. Le résultat est en portrait 540 × 960. Les trois tests courts (détecteur hors ligne, suppression des pixels source, parcours photo dans l’activité) ont été relancés sur la version finale : 3 réussites.

## Corrections issues des essais

La détection rapide manquait trop de visages sur la foule. L’export utilise maintenant le mode précis et quatre recadrages qui se chevauchent. Le décodage est limité à une image en attente : les essais ont montré que plusieurs images pouvaient sinon être écrasées lorsque le traitement ralentissait. Le test contrôle désormais explicitement que toutes les images de la vidéo d’entrée ont traversé le moteur.

## Limites constatées

Des visages très petits, de profil, flous ou cachés par un masque peuvent toujours échapper au détecteur. L’aperçu léger et l’export approfondi ne détectent pas nécessairement exactement les mêmes rectangles. Le rendu utilise quatre textures frontales dans des rectangles ; il ne reproduit pas le face swap propriétaire de Baskl. Aucune promesse d’anonymisation absolue ni de débit temps réel n’est faite.
