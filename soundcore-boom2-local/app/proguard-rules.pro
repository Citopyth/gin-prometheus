# kotlinx.serialization keeps its generated serializers in companion objects and
# synthetic classes; R8 needs to be told not to strip them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class de.privat.boom2local.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class de.privat.boom2local.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class de.privat.boom2local.protocol.**$$serializer { *; }
