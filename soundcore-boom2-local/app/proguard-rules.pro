# kotlinx.serialization reaches its generated serializers reflectively, so R8 has to be
# told to keep them. Without these the release build compiles happily and then fails at
# runtime, the moment the device profile is parsed.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# The library's own serializers.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Everything in the protocol package is serialized: the device profile and all of its
# nested sections.
-keepclassmembers class de.privat.boom2local.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class de.privat.boom2local.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class de.privat.boom2local.protocol.**$$serializer { *; }

# Enum constants are matched by name when parsing the profile JSON.
-keepclassmembers enum de.privat.boom2local.protocol.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
