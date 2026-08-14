plugins {
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
}

group = "github.lms.lemuel"
version = "0.1.0"

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
}
