# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.zzy.llmreader.**$$serializer { *; }
-keepclassmembers class com.zzy.llmreader.** {
    *** Companion;
}
-keepclasseswithmembers class com.zzy.llmreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
