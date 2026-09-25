import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from android/keystore.properties (gitignored; written by deploy/android-build.sh on the server).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// First-run server URL, baked into BuildConfig. Already-installed apps keep their saved address.
// Order: CHATTER_SERVER_URL, android/server.properties, https://$CHATTER_DOMAIN, then the example host.
fun envUrl(name: String) = System.getenv(name)?.trim().orEmpty()
val defaultServer = run {
    val explicit = envUrl("CHATTER_SERVER_URL")
    if (explicit.isNotEmpty()) return@run explicit
    val fromFile = Properties().apply {
        val f = rootProject.file("server.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }.getProperty("serverUrl")?.trim().orEmpty()
    if (fromFile.isNotEmpty()) return@run fromFile
    val domain = envUrl("CHATTER_DOMAIN").removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (domain.isNotEmpty()) return@run "https://$domain"
    "https://chat.example.com"
}.replace("\\", "\\\\").replace("\"", "\\\"")


android {
    namespace = "ink.jvm.chatter"
    compileSdk = 35

    defaultConfig {
        applicationId = "ink.jvm.chatter"
        minSdk = 26
        targetSdk = 35
        versionCode = 50
        versionName = "2.5.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")

    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The sherpa AAR ships every ABI; this app is arm64-only. libc++_shared.so also comes from WebRTC.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            excludes += setOf("**/armeabi-v7a/**", "**/x86/**", "**/x86_64/**")
            // Store native libraries compressed so the download is smaller. Install extracts them.
            useLegacyPackaging = true
        }
    }
    lint { checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val webrtcOriginal = configurations.create("webrtcOriginal") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
val patchedWebrtcAar = layout.buildDirectory.file("webrtc-patch/stream-webrtc-android-1.3.10.aar")
val patchWebRtc = tasks.register("patchWebRtc") {
    inputs.files(webrtcOriginal)
    inputs.file(rootProject.layout.projectDirectory.file("webrtc-patch/WebRtcAudioRecord.java"))
    inputs.file(rootProject.layout.projectDirectory.file("webrtc-patch/CaptionGate.java"))
    inputs.file(rootProject.layout.projectDirectory.file("webrtc-patch/patch.ps1"))
    outputs.file(patchedWebrtcAar)
    doLast {
        val aar = webrtcOriginal.files.first { it.extension == "aar" }
        val out = patchedWebrtcAar.get().asFile
        exec {
            environment(
                "JAVA_HOME",
                System.getenv("JAVA_HOME") ?: "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot",
            )
            commandLine(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                rootProject.layout.projectDirectory.file("webrtc-patch/patch.ps1").asFile.absolutePath,
                "-Aar", aar.absolutePath,
                "-Source", rootProject.layout.projectDirectory.file("webrtc-patch/WebRtcAudioRecord.java").asFile.absolutePath,
                "-Gate", rootProject.layout.projectDirectory.file("webrtc-patch/CaptionGate.java").asFile.absolutePath,
                "-Out", out.absolutePath,
            )
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    add("webrtcOriginal", "io.getstream:stream-webrtc-android:1.3.10")
    testImplementation("junit:junit:4.13.2")
    // Same WebRTC binary as 1.3.10, with WebRtcAudioRecord patched so call captions can
    // copy the direct microphone buffer. ByteBuffer.array() aborts the process on Android 16.
    implementation(files(patchWebRtc.map { patchedWebrtcAar.get().asFile }))
    // Prebuilt sherpa-onnx Android AAR (Kotlin API + libsherpa-onnx-jni + onnxruntime), v1.13.8.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    // On-device Qwen. No NDK in this tree; the LiteRT-LM AAR runs the .litertlm file on CPU.
    // 0.16.1 is the newest LiteRT-LM AAR this Kotlin 2.2.21 toolchain can read. 0.17 needs Kotlin 2.4.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // QR-code key migration between phones (scanner activity + encoder), pure Java, offline.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.3")
    val camera = "1.4.2"
    implementation("androidx.camera:camera-core:$camera")
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")
    implementation("androidx.camera:camera-view:$camera")
    implementation("androidx.camera:camera-video:$camera")
}
