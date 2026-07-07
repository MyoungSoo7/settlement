plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ ai-service 는 자체 DB(lemuel_ai) 를 소유하는 DB-per-service 대화형 AI 챗봇 서비스.
//   (docs/design/ai-service-phase1.md)
//   LLM 호출은 Spring AI 2.0(Anthropic) 을 쓰되 adapter/out/llm 에만 격리한다 — 자동설정(starter)
//   대신 라이브러리(spring-ai-anthropic)를 직접 물고 어댑터에서 수동 조립해, 키 미설정 시에도
//   부팅이 실패하지 않게 한다(ArchUnit 으로 격리 강제).
//   shared-common 은 JWT 스택만 제한 스캔(outbox/audit 미사용 — Phase 1 은 이벤트 발행 없음).

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")   // 버전드 내부 라이브러리(composite build 로 로컬 치환) — JWT

    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Spring AI 2.0 (Boot 4 전용 GA) — Anthropic Messages API 모델 라이브러리.
    // starter(자동설정) 미사용: AnthropicChatAdapter 가 직접 조립 (키 없이 부팅 가능해야 함).
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-anthropic")

    // Flyway — ai-service 는 자체 DB(lemuel_ai) 를 소유하므로 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Caffeine (rate limit 버킷 보관)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Rate limiting — LLM 비용 가드 (사용자별 분당/일일 상한)
    implementation("com.bucket4j:bucket4j-core:8.10.1")

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
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
