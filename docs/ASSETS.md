# Visages synthétiques embarqués

`app/src/main/assets/faces.webp` contient quatre portraits entièrement fictifs, créés avec l’outil intégré imagegen le 21 octobre 2026. Aucun visage utilisateur n’est envoyé à un service de génération. La génération n’a lieu qu’à la fabrication de cet asset ; l’application ne fait aucun appel réseau.

Prompt de génération (outil intégré, sans API ni CLI) :

> Use case: photorealistic-natural. Asset type: square face texture atlas for an offline face anonymization Android application. Create a precise borderless 2 by 2 grid of FOUR DIFFERENT entirely fictional AI-generated adult human faces, one face per equal square quadrant. Each quadrant is a tightly cropped frontal neutral-expression face, top of forehead at 5 percent of its tile, eyes at 38 percent, mouth at 70 percent, chin at 95 percent. Face fills full tile width, no shoulders, no clothes, no hands, no text, no labels, no gaps or borders between quadrants. Top left fictional woman light medium complexion; top right fictional man brown complexion; bottom left fictional man light complexion; bottom right fictional woman dark complexion. Flat diffuse neutral lighting, photographic skin texture, gaze straight ahead, symmetrical faces. These are replacement face textures that will be blurred by software; no likeness to real people. Output square 1024 by 1024 atlas.

L’original livré par l’outil, 1254 × 1254 pixels, est conservé dans `docs/assets/faces-source.png`. Une conversion WebP de 640 × 640 pixels est embarquée pour réduire la taille de l’APK. Les quatre cases sont découpées en mémoire, réduites à 160 × 160 et floutées avant d’être posées. Il s’agit de textures de remplacement opaques, pas d’un modèle de face swap neuronal.

Le portage iOS réutilise cet atlas en JPEG 640 × 640 dans `ios/ANON/Resources/faces.jpg` (environ 95 Ko). L’icône iOS est une rastérisation des mêmes tracés que `ic_anon.xml`. `ios/scripts/prepare-assets.py` reproduit ces conversions et fabrique une vidéo de test à partir de ces portraits fictifs, avec un son sinusoïdal et des métadonnées artificielles. Les médias personnels du dossier `video/` ne sont pas embarqués dans le projet iOS.
