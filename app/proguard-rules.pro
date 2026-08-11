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

# TDLib JNI — package is org.drinkless.tdlib (not org.drinkless.td)
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Kitteh IRC (Netty + MBassador). Optional backends are not on Android classpath.
-keep class org.kitteh.irc.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class io.netty.** { *; }
-dontwarn org.kitteh.irc.**
-dontwarn net.engio.mbassy.**
-dontwarn io.netty.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.**
-dontwarn javax.el.**
-dontwarn reactor.blockhound.**

# H2 (Matrix Trixnity store) — optional JVM APIs unused on Android
-dontwarn org.h2.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn javax.script.**
-dontwarn javax.security.auth.**
-dontwarn javax.tools.**
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.transform.stax.**
-dontwarn jdk.net.**
-dontwarn org.locationtech.jts.**

# Angus Mail / Jakarta Mail (Email protocol)
-keep class org.eclipse.angus.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn org.eclipse.angus.**
-dontwarn jakarta.mail.**
-dontwarn jakarta.activation.**
-dontwarn com.sun.mail.**
