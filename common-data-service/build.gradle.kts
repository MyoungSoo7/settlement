plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ common-data-service 는 공공데이터포털(data.go.kr)의 임의 OpenAPI 를 코드 변경 없이
//   "데이터소스"로 등록해 수집·저장·공개 조회하는 범용 커넥터다. economics(ECOS)·market(금융위)이
//   API 하나마다 서비스를 신설한 것과 달리, 표준 data.go.kr 응답 봉투(response.header/body.items)를
//   따르는 API 라면 등록만으로 커버한다. 자체 DB(lemuel_commondata) 소유 + 독립 부팅.
//   회원/주문 컨텍스트·Kafka 이벤트와 무관하므로 shared-common(JWT·Outbox·Kafka)을 의도적으로
//   물지 않는다 — 자체 최소 SecurityConfig 보유.

dependencies {
    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Flyway — 자체 DB(lemuel_commondata) 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Caffeine (소스 카탈로그/레코드 조회 캐시)
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core")
    // 1.4.x — Java 25 클래스파일(major 69) 파싱 지원 (1.3.0 은 전부 스킵되어 no-classes 실패)
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
