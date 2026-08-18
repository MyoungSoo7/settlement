import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// reconciliation-service — STANDALONE Gradle build (NOT part of settlement's multi-module build).
// Pinned to JDK 21 toolchain + Kotlin 2.0.x + Spring Boot 3.3.x to dodge the JDK-25 / Kotlin-2.3
// toolchain landmine. See README.md.

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "github.lms.lemuel"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot (MVC + actuator)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Prometheus 레지스트리 — 없으면 actuator 가 /actuator/prometheus 를 아예 만들지 않는다.
    // application.yml 의 exposure 에 prometheus 가 적혀 있었지만 레지스트리가 없어 404 였다.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Kotlin essentials
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Coroutines — the POINT of this service (concurrent multi-source fetch)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
