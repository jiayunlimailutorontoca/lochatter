# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ink.jvm.chatter.**$$serializer { *; }
-keepclassmembers class ink.jvm.chatter.** { *** Companion; }
-keepclasseswithmembers class ink.jvm.chatter.** { kotlinx.serialization.KSerializer serializer(...); }
# Every @Serializable model and the frames: keep them whole so polymorphic "t" dispatch keeps working
-keep @kotlinx.serialization.Serializable class ink.jvm.chatter.** { *; }
-keep class ink.jvm.chatter.data.** { *; }
# Components referenced from the manifest / by name
-keep class ink.jvm.chatter.ChatterApp { *; }
-keep class ink.jvm.chatter.ui.MainActivity { *; }
-keep class ink.jvm.chatter.service.** { *; }
-keep class ink.jvm.chatter.widget.** { *; }
-keep class ink.jvm.chatter.call.CallActionReceiver { *; }
-keep class ink.jvm.chatter.util.UpdateChecker$InstallReceiver { *; }
# WebRTC: JNI callbacks resolve Java methods by name
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# okhttp / okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# coil (decoders are looked up by class)
-dontwarn coil.**
-keep class coil.decode.** { *; }
# zxing (embedded scanner activity is referenced from the manifest merge)
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
# On-device SenseVoice. JNI looks classes up by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
# On-device Qwen via LiteRT-LM. JNI looks classes up by name.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.gson.**
# security-crypto pulls Tink, which references annotations that are not on the compile classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.crypto.tink.**
# Keep names: shrink and optimise only. Crash traces from 「导出诊断信息」 must stay readable without mapping files.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
