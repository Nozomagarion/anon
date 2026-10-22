# Validation du portage iOS — 21 octobre 2026

## Exécuté sur ce poste Windows / WSL

- Compilation de `ANONCore` avec le compilateur officiel Swift 6.0.3 sous Ubuntu/WSL : réussie.
- **7 tests XCTest, 0 échec** : limites des rectangles, union, suivi après déplacement, rétention et expiration, retour temporel, suppression des métadonnées PNG, suppression MP4 version 0/version 1 avec blocs de taille 64 bits, rejet des conteneurs tronqués ou non pris en charge.
- Analyse syntaxique de tous les fichiers Swift iOS et tests avec `swiftc -frontend -parse` : réussie. Cette vérification ne résout pas les API des SDK Apple et ne remplace pas une compilation iOS.
- Lecture du projet avec un parseur OpenStep indépendant : réussie ; fichiers référencés présents. Plists, manifeste de confidentialité, catalogue d’assets et scripts shell valides.
- Nettoyeur Swift compilé et exécuté sur un véritable MP4 H.264/AAC synthétique, 18 images, 0,6 s, rotation 90°, avec GPS, titre, auteur et date injectés. FFprobe ne retrouve après nettoyage que les marques de conteneur indispensables et la langue `und`. Orientation et durée conservées.
- Comparaison indépendante FFmpeg `framemd5` avant/après nettoyage : **images vidéo décodées et audio décodé identiques**. Ce contrôle porte sur le nettoyage du conteneur, pas sur le pipeline d’anonymisation iOS.
- Nettoyeur Swift exécuté sur un PNG réel avec EXIF, auteur, appareil, date et XMP injectés : pixels identiques après nettoyage, aucun EXIF/XMP/texte restant, confirmé avec Pillow.

## Préparé, pas encore exécuté

`ANONTests/ProcessingTests.swift` contient trois tests qui nécessitent Xcode/iOS :

1. Indépendance des pixels d’un masque par rapport à deux couleurs de source différentes ; conservation du fond.
2. Export réel via Vision/Core Image/AVFoundation de la fixture en portrait : conservation des images et de l’audio, orientation, comparaison de pixels remplacés et suppression des métadonnées.
3. Annulation sans renvoi d’un export publiable.

Les 7 tests du code commun sont aussi inclus dans la cible Xcode. `scripts/verify-macos.sh` construit l’app et lance l’ensemble sur simulateur.

**Aucune compilation avec le SDK iOS, exécution de SwiftUI, mesure de performance iPhone ou installation physique n’a été effectuée. Aucun IPA signé n’a été produit.** Ces étapes nécessitent un Mac/Xcode et la configuration de signature Apple pour l’appareil.
