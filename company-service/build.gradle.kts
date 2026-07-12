plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ company-service 는 기업 뉴스 기사·평판 조회 서비스 (ADR 0023).
//   자체 DB(lemuel_company) 소유 + 독립 부팅.
//   Phase 3 부터 shared-common 의 Outbox·멱등 인프라를 물어 평판 등급 변동을 Kafka 이벤트로 발행한다
//   (common.outbox 만 스캔 — JWT/audit 스택은 여전히 제외, 자체 SecurityConfig 유지).

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")   // 버전드 내부 라이브러리(composite build 로 로컬 치환) — Outbox/멱등

    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Flyway — 자체 DB(lemuel_company) 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka — 평판 등급 변동 Outbox 이벤트(lemuel.company.reputation_changed) 발행
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Caffeine (기업/기사 조회 캐시)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Prometheus
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql:42.7.3")

    // dotenv
    implementation("io.github.cdimascio:java-dotenv:5.2.2")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Test
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    // Java 25 클래스 파싱은 ArchUnit 1.4.x 부터 지원
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
