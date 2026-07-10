plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ account-service (계정계) 는 자체 DB(lemuel_account) 를 소유하는 DB-per-service 독립 부팅 서비스.
//   loan / investment / settlement 이 발행하는 Kafka 이벤트를 소비해
//   전사 복식부기 계정원장(GL)으로 집계한다 — 대출·투자·정산 잔액/시산표 조회 API 제공.
//   (loan-service 패턴 미러링, 코드·DB 의존 0 — 이벤트로만 연계)

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")   // JWT·멱등컨슈머 (composite build 로 로컬 치환)
    testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))   // 이벤트 계약 스키마·검증기 (ADR 0024)

    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Flyway — 자체 DB(lemuel_account) 마이그레이션 직접 책임
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka (loan/investment/settlement 이벤트 consume 전용 — 발행 없음)
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Caffeine
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
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
