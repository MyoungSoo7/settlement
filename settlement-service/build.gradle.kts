plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("info.solidsoft.pitest") version "1.19.0"
}

// Standalone 모드 (ADR 0020 Phase 0): settlement-service 는 자체 실행가능 jar 로 독립 기동(:8082).
// 이전 라이브러리 모드(order-service fat jar 번들)는 해제 — bootJar 가 Spring Boot 플러그인
// 기본값(활성)으로 동작하며, 진입점은 SettlementServiceApplication 이다.

// httpcore5 5.3.6 은 CVE-2026-54399(HIGH, 수정판 5.4.3). Boot 4.0.7 BOM 이 httpclient5 를 5.5.2 로,
// httpcore5 를 5.3.6 으로 관리한다 — Boot 상향으로는 안 풀리고, 그렇다고 httpcore5 만 올리면
// httpclient5 5.5.x 와 짝이 어긋난다. 상류가 맞춰 낸 조합(httpclient5 5.6 + httpcore5 5.4.x)으로 함께 올린다.
// 마침 elasticsearch-rest5-client 9.2.8 이 원래 요구하는 것도 httpclient5 5.6 인데 BOM 이 5.5.2 로
// 내리고 있었다 — 이 오버라이드는 그 다운그레이드도 되돌린다. ES 를 쓰는 모듈은 이 서비스뿐이다
// (전 모듈 build.gradle.kts 검색 실측).
//
// `constraints` 가 아니라 BOM 프로퍼티인 이유: io.spring.dependency-management 는 BOM 관리 버전을
// resolutionStrategy 룰로 강제해서 Gradle constraint 를 이긴다. 실측으로 constraint 는 무시됐고
// (dependencyInsight: "Selected by rule", httpclient5 5.5.2 유지) 프로퍼티 오버라이드만 먹혔다.
extra["httpclient5.version"] = "5.6"
extra["httpcore5.version"] = "5.4.3"

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")   // 버전드 내부 라이브러리(composite build 로 로컬 치환)
    testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))   // 이벤트 계약 스키마·검증기·정본 샘플 (ADR 0024)

    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // 전문 스펙 YAML 파싱 (ADR 0033) — Boot BOM 이 버전 관리, 전이 의존이 아니라 명시 선언
    implementation("org.yaml:snakeyaml")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka (PaymentCaptured 등 consume)
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    // Elasticsearch (정산 검색/집계)
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // Spring Batch (일/월 정산)
    implementation("org.springframework.boot:spring-boot-starter-batch")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // QueryDSL
    implementation("com.querydsl:querydsl-jpa:5.0.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.0.0:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    // iText (정산서 PDF)
    implementation("com.itextpdf:itext-core:8.0.5")
    implementation("com.itextpdf:font-asian:8.0.5")

    // Caffeine
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Rate limiting
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

    // MapStruct
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

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
    // ArchUnit 1.4.x 부터 Java 25 바이트코드 파싱 지원 — 1.3.0 은 클래스 임포트를 조용히 스킵해
    // allowEmptyShould(true) 규칙이 공허하게 통과(가드 무력화)한다. account-service 와 동일 버전으로 정렬.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:elasticsearch")
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

// 전문 스펙(telegram/firmbanking/*.yaml) → VO·코덱 소스 재생성 (ADR 0033 Phase 2).
// 생성물은 src/main 에 커밋되고, 스펙과의 일치는 TelegramGeneratedSourcesTest 가 매 빌드 대조한다.
tasks.register<Test>("generateTelegramSources") {
    group = "build"
    description = "전문 스펙 YAML 에서 VO·코덱 소스를 재생성한다"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*TelegramGeneratedSourcesTest*") }
    systemProperty("telegram.codegen.write", "true")
    outputs.upToDateWhen { false }
    // 부모 build 가 모든 Test 태스크에 jacocoTestReport 를 finalizer 로 붙이는데,
    // 그 리포트는 test 태스크에 의존한다 → 소스 재생성 한 번에 전체 스위트가 딸려온다. 끊는다.
    setFinalizedBy(emptyList<Task>())
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
}

val querydslDir = layout.buildDirectory.dir("generated/querydsl")

tasks.withType<JavaCompile>().configureEach {
    options.generatedSourceOutputDirectory.set(querydslDir.get().asFile)
}

tasks.named("clean") {
    doLast {
        delete(querydslDir)
    }
}

sourceSets {
    named("main") {
        java.srcDir(querydslDir)
    }
}

// STEP 0 (품질 씨앗 — 테스트 깊이 측정): settlement 도메인 뮤테이션 베이스라인 측정용 PIT 배선.
// docs/etc/next.md 6번 계획 반영. targetClasses 는 도메인 패키지로만 한정.
// 베이스라인 단계라 mutationThreshold(=60 게이트)는 아직 걸지 않는다 — 현재 점수 X 를 있는 그대로 관측.
pitest {
    targetClasses.set(listOf("github.lms.lemuel.settlement.domain.*"))
    targetTests.set(listOf("github.lms.lemuel.settlement.domain.*"))
    pitestVersion.set("1.22.1")
    junit5PluginVersion.set("1.2.2")
    threads.set(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    // PIT minion JVM 은 test 태스크의 jvmArgs 를 상속하지 않으므로 mockito agent 를 방어적으로 전달
    jvmArgs.set(listOf("-javaagent:${configurations.getByName("mockitoAgent").asPath}"))
}

// src/main/resources/settlement-copilot 은 settlement(8082)·order(8088) 공개 API 를 소비하는 제출물 플러그인(MCP·스킬·가드)이다.
// 서비스 런타임이 읽는 리소스가 아니므로 배포 jar 에 실리지 않도록 리소스 처리에서 제외한다.
tasks.named<ProcessResources>("processResources") {
    exclude("settlement-copilot/**")
}
