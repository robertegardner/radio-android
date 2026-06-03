# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.rg2.radio.** {
    *** Companion;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class io.rg2.radio.** {
    <fields>;
}
