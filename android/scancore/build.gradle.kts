// Image-processing core, shared by the Android app and PC-side tests.
// Uses only the OpenCV Java API: on Android it is provided by org.opencv:opencv,
// on the PC by openpnp's desktop build (same org.opencv.* classes, natives bundled).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Bytecode level of the Android app; compiled with whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    compileOnly("org.openpnp:opencv:4.9.0-0")
    testImplementation("org.openpnp:opencv:4.9.0-0")
    // ONNX Runtime (MIT) for learned models such as DocAligner; the app brings onnxruntime-android
    // (1.31: 16 KB aligned). The PC tests stay on 1.19.2: newer Windows builds crash in an older msvcp140.dll.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    testImplementation(kotlin("test"))
    testImplementation("org.verapdf:validation-model:1.26.2") // PDF/A validation of the PDF writer (reference validator of the PDF Association)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "16g" // the series evaluation processes many photos in parallel
    // no maxParallelForks: openpnp OpenCV deletes the native-library temp folders of other JVMs while they load
    systemProperty("testfotos", System.getProperty("testfotos") ?: "C:/Users/Peter/Desktop/testfotos")
    // diagnostic runs (DebugRunTest): forward the folder and never serve them from the cache
    System.getProperty("debugdir")?.let { systemProperty("debugdir", it); inputs.property("debugdir", it); outputs.upToDateWhen { false } }
    // evaluation of the real photo series (SeriesEval): ./gradlew :scancore:test -Deval=true --tests '*SeriesEval'
    System.getProperty("eval")?.let { systemProperty("eval", it); inputs.property("eval", it); outputs.upToDateWhen { false } }
    for (key in listOf("evalRoot", "evalOut", "evalThreads", "lang", "evalPhoto", "evalSplit", "ultimatepdfscanner.debug", "ocr", "paddleImage", "paddleHeight", "pdfFile")) System.getProperty(key)?.let { systemProperty(key, it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
