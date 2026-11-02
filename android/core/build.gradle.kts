plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Live checks against the real servers only when asked: `-Plive`.
    systemProperty("kite.live", providers.gradleProperty("live").isPresent.toString())
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
