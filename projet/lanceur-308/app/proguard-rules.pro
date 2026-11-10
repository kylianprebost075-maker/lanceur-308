# Filament (moteur 3D) et SceneView : classes appelées par le code natif (JNI), à ne pas renommer.
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn com.google.android.filament.**

# Journal des plantages (v0.20) : garder les vrais noms des classes, des fonctions et les numéros de ligne,
# sinon le fichier écrit sur la clé est illisible (« a.b.c »). Le code inutilisé reste retiré.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
