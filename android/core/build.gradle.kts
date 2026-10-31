// Card recognition and data logic that doesn't need Android: compiled against the
// OpenCV Java API, so it runs on the phone (OpenCV for Android) and in desktop
// tests (OpenPnP's OpenCV build, which bundles the native libraries).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.opencv.desktop)
    compileOnly(libs.json)
    testImplementation(libs.opencv.desktop)
    testImplementation(libs.json)
    testImplementation(libs.junit)
}

tasks.test {
    // Benchmarks read cached card data and test footage from the repo's tests/.cache.
    systemProperty("cacheDir", rootProject.projectDir.resolve("../tests/.cache").absolutePath)
    // -Pbench=<sequence folder> -PbenchSets=sv3pt5,base1 pick what RecognitionBench runs on
    providers.gradleProperty("bench").orNull?.let { systemProperty("bench", it) }
    providers.gradleProperty("benchSets").orNull?.let { systemProperty("benchSets", it) }
    providers.gradleProperty("benchVerbose").orNull?.let { systemProperty("benchVerbose", it) }
    providers.gradleProperty("debugFrames").orNull?.let { systemProperty("debugFrames", it) }
    outputs.upToDateWhen { false }
    maxHeapSize = "3g"
    testLogging { showStandardStreams = true }
}
