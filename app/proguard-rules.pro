# Ktor / coroutines / serialization keep rules.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.serialization.**
-dontwarn java.lang.management.**
-dontwarn reactor.blockhound.**

# kotlinx.serialization generated serializers.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.seedds.vplayer.**$$serializer { *; }
-keepclassmembers class com.seedds.vplayer.** {
    *** Companion;
}

# Ktor engine + plugin discovery.
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.engine.** { *; }
