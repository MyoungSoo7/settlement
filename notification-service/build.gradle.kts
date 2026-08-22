plugins {
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    jacoco
}

group = "github.lms.lemuel"
version = "0.1.0"

// 커버리지 측정에서 뺄 것 — 부트스트랩 클래스뿐이다. 자바 모듈처럼 adapter 전체를 빼면
// 이 서비스는 잴 코드가 domain 몇 개만 남아 게이트가 사실상 공전한다(이 모듈은 어댑터가 본체다).
val coverageExclusions = listOf(
    "**/NotificationServiceApplication*",
)

java {
    toolchain {
        // Pin to JDK 21 to dodge the JDK-25 / Kotlin-2.3 toolchain landmine.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    // Web (MVC) + actuator
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Prometheus 노출 — 이 서비스는 DLT 격리 카운터(notification.kafka.dlt.published)를 내지만
    // 레지스트리가 없어 /actuator/prometheus 가 아예 뜨지 않았고, 그래서 격리된 알림을 아무도 보지 못했다.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Kafka inbound
    implementation("org.springframework.kafka:spring-kafka")

    // Mail (jakarta.mail) for the SMTP EmailChannel
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // JWT (HS256) — the push stream verifies the platform token itself; this
    // service has no shared-common on its classpath. Same library and version
    // as shared-common's JwtUtil so the two agree on parsing/validation.
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // EmbeddedKafka — DLT 격리는 브로커 없이는 "설정이 맞다"까지만 검증된다. 재시도 소진 후
    // 실제로 <topic>.DLT 에 레코드가 도착하는지는 실 브로커로만 증명된다(settlement 의 DlqEndToEndTest 와 동형).
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.named("jacocoTestReport"))
}

// 커버리지 게이트 — 자바 모듈(루트 build.gradle.kts LINE 0.90)과 같은 기준선.
// 폴리글랏이라고 게이트가 없으면 "테스트는 있는데 얼마나 덮는지는 아무도 모르는" 상태가 된다.
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
