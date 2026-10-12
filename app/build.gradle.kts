plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "br.dev.gui.integra"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.dev.gui.integra"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.2"

        // Permite sobrescrever no build: ./gradlew assembleDebug -PintegraUrl=https://...
        val integraUrl = (project.findProperty("integraUrl") as String?) ?: "https://integra.infra.gui.dev.br"
        buildConfigField("String", "HOME_URL", "\"$integraUrl\"")
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug para facilitar a instalação via adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// O POM do SDK do OpenObserve (0.1.0) publica as dependências de teste como runtime.
configurations.all {
    exclude(group = "junit")
    exclude(group = "org.junit.jupiter")
    exclude(group = "org.junit.vintage")
    exclude(group = "org.junit.platform")
    exclude(group = "org.mockito")
    exclude(group = "org.mockito.kotlin")
    exclude(group = "org.assertj")
    exclude(group = "fr.xgouchet.elmyr")
    exclude(module = "unit")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.browser:browser:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Reconhecimento offline do "Ei Integra" (mesmo motor e modelo do assistente web).
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Verificação de locutor ("é a minha voz?") no "Ei Integra": roda o mesmo
    // modelo ONNX do assistente web, localmente. Ver voice/SpeakerModel.kt.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // RUM, logs e session replay do OpenObserve.
    val o2 = "0.1.0"
    implementation("ai.openobserve:o2-sdk-android-rum:$o2")
    implementation("ai.openobserve:o2-sdk-android-logs:$o2")
    implementation("ai.openobserve:o2-sdk-android-session-replay:$o2")
    implementation("ai.openobserve:o2-sdk-android-session-replay-compose:$o2")
    implementation("ai.openobserve:o2-sdk-android-okhttp:$o2")
    implementation("ai.openobserve:o2-sdk-android-compose:$o2")
}
