# Regole minime: Hilt, Room e Compose forniscono le proprie regole consumer.
-keep class it.spese.domain.** { *; }
-keep class it.spese.core.model.** { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker { public <init>(...); }
-dontwarn org.xmlpull.v1.**
-dontwarn javax.xml.stream.**
