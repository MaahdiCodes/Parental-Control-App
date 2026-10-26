plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
}

// Try the YouTube link from this PC (no phone needed), e.g.:
//   gradlew :core:loungeProbe -PprobeArgs="pair 123456789012"
//   gradlew :core:loungeProbe -PprobeArgs="watch <screenId> 60"
tasks.register<JavaExec>("loungeProbe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tvguardian.core.youtube.LoungeProbeKt")
    args = (project.findProperty("probeArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
