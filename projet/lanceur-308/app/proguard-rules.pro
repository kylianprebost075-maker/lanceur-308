# Filament (moteur 3D) et SceneView : classes appelées par le code natif (JNI), à ne pas renommer.
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }
-dontwarn com.google.android.filament.**
