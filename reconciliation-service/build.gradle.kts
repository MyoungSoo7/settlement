import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// reconciliation-service — STANDALONE Gradle build (NOT part of settlement's multi-module build).
// Pinned to JDK 21 toolchain + Kotlin 2.0.x + Spring Boot 3.3.x to dodge the JDK-25 / Kotlin-2.3
// toolchain landmine. See README.md.

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    jacoco
}

group = "github.lms.lemuel"
version = "0.1.0-SNAPSHOT"

// 커버리지 측정에서 뺄 것 — 부트스트랩 클래스뿐이다. 자바 모듈처럼 adapter 전체를 빼면
// 이 서비스는 잴 코드가 거의 남지 않아 게이트가 공전한다(이 모듈은 어댑터가 본체다).
val coverageExclusions = listOf(
    "**/ReconciliationApplication*",
)

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
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
    finalizedBy(tasks.named("jacocoTestReport"))
}

// 커버리지 게이트 — 자바 모듈(루트 build.gradle.kts LINE 0.90)과 같은 기준선.
jacoco {
    toolVersion = "0.8.12"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(classDirectories.files.map { dir ->
        fileTree(dir) { exclude(coverageExclusions) }
    })
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    classDirectories.setFrom(classDirectories.files.map { dir ->
        fileTree(dir) { exclude(coverageExclusions) }
    })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
    doFirst {
        val measured = classDirectories.asFileTree.matching { include("**/*.class") }.files.size
        require(measured > 0) { "커버리지 측정 대상이 0개다 — 게이트가 공전한다." }
    }
}

tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
