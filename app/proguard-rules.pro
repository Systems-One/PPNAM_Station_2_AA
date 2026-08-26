# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- Gson ----
# None of our request/response DTOs use @SerializedName, so Gson matches JSON keys
# against the Kotlin property names directly via reflection. Without these keeps,
# R8 renames those fields and the MQTT wire contract with the WPF sibling app breaks
# silently (fields deserialize to null instead of failing loudly).
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.stream.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.mitas.ppnam.station2aa.data.mqtt.dto.** { *; }
-keep class com.mitas.ppnam.station2aa.data.mqtt.MqttRequest { *; }
-keep class com.mitas.ppnam.station2aa.data.mqtt.MqttResponseMessage { *; }

# ---- HiveMQ MQTT client (shaded) ----
# Shaded jar, not an AAR, so its consumer rules (if any) aren't picked up automatically.
# It relocates its own Netty copy internally, which does TLS/ALPN provider lookup and
# transport selection by reflection. Broad-keeping trades away shrinking the largest
# single dependency in the app for not risking a runtime ClassNotFoundException against
# the production broker that can't be caught by a unit test suite.
-keep class com.hivemq.** { *; }
-dontwarn com.hivemq.**
# Netty's optional Project Reactor BlockHound debug hook — not on the runtime classpath
# and never invoked outside of BlockHound-instrumented test runs.
-dontwarn io.netty.util.internal.Hidden$NettyBlockHoundIntegration
