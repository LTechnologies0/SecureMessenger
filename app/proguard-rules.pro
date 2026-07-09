# Smack + extensions (smackx), jxmpp, minidns.
# NOTE: `org.jivesoftware.smack.**` does NOT match `org.jivesoftware.smackx.**`,
# so the extension managers referenced from smack-config were being stripped in
# minified release builds (ClassNotFoundException at runtime). Keep the whole vendor tree.
-keep class org.jivesoftware.** { *; }
-dontwarn org.jivesoftware.**
-keep class org.jxmpp.** { *; }
-dontwarn org.jxmpp.**
-keep class org.minidns.** { *; }
-dontwarn org.minidns.**
-keep class org.igniterealtime.** { *; }
-dontwarn org.igniterealtime.**

# Tink / crypto (transitive via security libs)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Ktor / OkHttp
-dontwarn io.ktor.**
-dontwarn okhttp3.**

# Trixnity Matrix SDK + kotlinx.serialization models
-keep class net.folivo.trixnity.** { *; }
-keepclassmembers class net.folivo.trixnity.** { *; }
-keep,includedescriptorclasses class net.folivo.trixnity.**$$serializer { *; }
-keepnames class net.folivo.trixnity.**$$serializer { *; }
-dontwarn net.folivo.trixnity.**

# TDLib JNI (when present)
-keep class org.drinkless.td.** { *; }
