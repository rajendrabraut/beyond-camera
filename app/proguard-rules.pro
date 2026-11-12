# Project-specific R8 rules. CameraX, Compose, Coil and ML Kit ship their own consumer rules.

# ZXing looks up its readers directly; keep the decoder and encoder classes intact.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
