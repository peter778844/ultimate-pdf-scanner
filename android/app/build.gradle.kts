plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.ultimatepdfscanner.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.ultimatepdfscanner.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // all common CPUs by default; `-Pabi=arm64-v8a` builds a smaller APK for one phone
        val abis = providers.gradleProperty("abi").orNull?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = true // R8: drops unused library code (the dex shrinks from ~86 MB)
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // personal sideload build
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
    androidResources { noCompress += listOf("traineddata") }
}

dependencies {
    implementation(project(":scancore"))
    implementation("org.opencv:opencv:4.14.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0") // learned page corners (DocAligner), MIT
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2") // EXIF of imported pictures (the platform class has known bugs on old Android)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    val camerax = "1.6.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.zxing:core:3.5.3") // QR/barcode reading and QR generation, Apache 2.0
    implementation("com.google.mlkit:face-detection:16.1.7") // bundled model, works offline (passport photos)
}
