# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- Commons Imaging ---
# The library references java.awt / javax.imageio, neither of which exists on
# Android. The IPTC/XMP rewrite path never executes those instructions (see the
# Phase 0 notes in the plan), but R8 still needs to be told not to fail on the
# dangling references.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.commons.imaging.color.**

# Keep the metadata rewrite entry points; they are reached reflectively-free but
# shrinking has previously stripped the IPTC constant tables.
-keep class org.apache.commons.imaging.formats.jpeg.iptc.** { *; }
-keep class org.apache.commons.imaging.formats.jpeg.xmp.** { *; }
-keep class com.adobe.internal.xmp.** { *; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.mrlaki5.mystockmanager.** {
    *** Companion;
}
-keepclasseswithmembers class com.mrlaki5.mystockmanager.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Tink (pulled in by androidx.security:security-crypto) ---
# Tink is compiled against Error Prone's annotations, which are compile-time only
# and not shipped in any runtime artifact.
-dontwarn com.google.errorprone.annotations.**
