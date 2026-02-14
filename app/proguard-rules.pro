# ProGuard rules for Voice2Text Android app

# Vosk
-keep class com.alphacephei.vosk.** { *; }
-dontwarn com.alphacephei.vosk.**

# Keep app classes
-keep class com.voice2text.android.** { *; }

# Preserve line numbers for debugging
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
