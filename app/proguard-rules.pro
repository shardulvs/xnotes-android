# xnotes R8 / ProGuard rules.
#
# Release builds run R8 (isMinifyEnabled = true). Almost everything is safe to shrink and rename:
# the app uses no reflection, and the .xnote bundle and JSON stores key off string literals, never
# class or field names. Only the cases below would break, so only they are kept. Keep this list
# tight: broad -keep rules cost APK size and, by pinning names, are the usual reproducibility risk.

# --- PdfBox-Android (com.tom-roush:pdfbox-android) ----------------------------------------------
# PdfBox loads fonts, glyph lists and CMaps reflectively from its own bundled resources, so its
# classes must survive shrinking and keep their names. It also references desktop java.awt / javax
# types that do not exist on Android, on code paths the app never reaches; silence those so R8's
# missing-class checks don't fail the build.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.apache.**
-dontwarn org.bouncycastle.**

# --- Filen sync ---------------------------------------------------------------------------------
# WorkManager instantiates the worker reflectively by class name, and argon2kt binds its native
# methods through JNI by class/method name. Renaming either breaks it at runtime.
-keep class com.xnotes.sync.filen.FilenSyncWorker { *; }
-keep class com.lambdapioneer.argon2kt.** { *; }

# --- Persisted model enums ----------------------------------------------------------------------
# Saved enums match on their explicit `id` string (a literal, so R8-safe), but PageSize also falls
# back to Enum.name(). Keep the core enums whole so renaming can never change a name written to, or
# read back from, a saved document — the .xnote format must stay forward/backward compatible.
-keep enum com.xnotes.core.** { *; }

# --- ONNX Runtime (handwritten maths add-on) ----------------------------------------------------
# The native library calls back into its Java classes through JNI by name.
-keep class ai.onnxruntime.** { *; }

# --- PDFium JNI ---------------------------------------------------------------------------------
# pdf_jni.cpp reads CancelToken.isCancelled by name while a render runs.
-keepclassmembers class com.xnotes.platform.CancelToken { boolean isCancelled; }
