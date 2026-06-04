# KDE Connect mini-client использует рефлексию BouncyCastle — оставляем классы.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
