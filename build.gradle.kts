import org.flywaydb.gradle.FlywayExtension

plugins {
    java
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("org.sonarqube") version "5.1.0.4882"
    // id("io.snyk.gradle.plugin.snykplugin") version "0.6.1"
    id("org.flywaydb.flyway") version "11.7.2"
}

group = "github.lms"
version = "0.0.1-SNAPSHOT"
description = "lemuel"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Boot 4 에서 FlywayAutoConfiguration 이 별도 모듈로 분리됨 — 없으면
    // 앱 기동 시 마이그레이션이 돌지 않는다. starter 없이 모듈 직접 선언.
    implementation("org.springframework.boot:spring-boot-flyway")

    // Kafka (실 브로커 — Redpanda/Apache Kafka 호환).
    // app.kafka.enabled=true 일 때만 KafkaOutboxPublisher / 컨슈머가 활성화된다.
    // Boot 4 에서 KafkaAutoConfiguration 이 별도 모듈(spring-boot-kafka)로 분리 —
    // 없으면 KafkaTemplate 빈이 자동생성되지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")
    runtimeOnly("org.postgresql:postgresql:42.7.3")

    // SpringDoc OpenAPI — 3.x 계열은 Boot 4 + Spring 7 + Spring Data 신 API 에 호환.
    // 이전 2.8.0 은 QuerydslProvider 가 구 Spring Data TypeInformation 을 참조해 @SpringBootTest 컨텍스트 로드 실패.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    implementation("io.github.cdimascio:java-dotenv:5.2.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Elasticsearch
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // Spring Batch
    implementation("org.springframework.boot:spring-boot-starter-batch")

    // Mail
    implementation("org.springframework.boot:spring-boot-starter-mail")

    implementation("com.querydsl:querydsl-jpa:5.0.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.0.0:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")


    // Ghostscript 연동 (ProcessBuilder로 gs CLI 호출)
    // gs 바이너리는 Docker 이미지에 설치됨 (apk add ghostscript)
    // iText8 AGPL: PDF 생성 (정산서 등)
    implementation("com.itextpdf:itext-core:8.0.5")
    // 한국어(CJK) 폰트 지원 - HYGoThic-Medium 등 내장 폰트 제공
    implementation("com.itextpdf:font-asian:8.0.5")

    // Cache (Caffeine)
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Resilience4j — Toss PG 호출에 CircuitBreaker + Retry 적용 (AOP 기반)
    // Boot 4.0 에서 spring-boot-starter-aop 가 퍼블리시되지 않으므로 제거.
    // spring-aop(7.0.x) + aspectjweaver 는 다른 스타터(data-jpa → spring-aspects)를 통해 전이적으로 확보된다.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // 구조화 로그 (T2-④) — JSON 인코더. spring 프로파일에서만 활성, local 은 평문.
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Rate Limiting (T2-⑤) — in-memory Bucket4j. 단일 노드 가정, 클러스터는 Redis backed 로 추후 교체.
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // prometheus
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

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
    // Boot 4 에서 WebMvcTest 슬라이스에 Jackson 자동설정이 기본 포함되지 않아 명시 추가.
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    // Boot 4 에서 @DataJpaTest / @AutoConfigureTestDatabase 가 별도 모듈로 분리되어 스타터로 추가.
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Mockito 를 명시적 javaagent 로 주입 (JDK 21+ 필수, JDK 25 에서 self-attach 불가)
    // 동시에 JaCoCo 에이전트와의 충돌을 방지해 instrumentation 이 정상 적용되도록 한다
    testImplementation("org.mockito:mockito-core")
    // ArchUnit: 헥사고날 경계 규칙을 테스트로 강제
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    // Testcontainers: 실 Postgres 에서 Flyway+Hibernate schema validation 을 CI 에서 검증.
    // Boot 4.0 BOM 은 testcontainers 버전을 관리하지 않으므로 BOM 을 명시적으로 임포트.
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
    // Mockito 5 를 자체 에이전트로 로드 — self-attach 로 JaCoCo instrumentation 이 소실되는 이슈 방지
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            // 2026-04-23: report + observability + SellerTier + SellerCycle + Audit + RateLimit +
            //             PII + AuditLog IT + ADR 추가로 INSTRUCTION 38.06%, LINE 41.34%, CLASS 62.07%.
            // CLAUDE.md 의 70% 목표까지의 잔여 격차는 주로 아래 영역 — 전부 인프라성 테스트.
            //   - QueryDSL generated code (Q* 클래스, ~1,400 줄) → 실제 QueryDSL 통합 테스트 필요
            //   - JPA Entity getter/setter 및 @PrePersist/@PreUpdate 훅 → @DataJpaTest 통합 테스트 필요
            //   - PDF/Elasticsearch/Batch 어댑터 → Testcontainers 기반 통합 테스트 필요
            //   - 컨트롤러 다수 → @WebMvcTest 테스트 필요
            // 회귀 방지선을 0.38 로 갱신. Persistence adapter 통합 테스트 확충은 별도 작업.
            limit {

                minimum = "0.38".toBigDecimal() // TODO: 통합 테스트 확충 후 0.70 까지 단계적 상향

            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

sonar {
    properties {
        property("sonar.projectKey", "MyoungSoo7_settlement")
        property("sonar.organization", "myoungsoo7")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths", "${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.java.coveragePlugin", "jacoco")
        property("sonar.qualitygate.wait", false)
        property("sonar.junit.reportPaths", "${layout.buildDirectory.get()}/test-results/test")
        property("sonar.java.source", "25")

    }
}

// Snyk Configuration
// snyk {
//     // Snyk 설정
//     setArguments("--all-projects")
//     setSeverity("low") // low, medium, high, critical
//     setApi(System.getenv("SNYK_TOKEN") ?: "")
//     setAutoDownload(true)
//     setAutoUpdate(true)
// }

extensions.configure<FlywayExtension> {
    url = System.getenv("SPRING_DATASOURCE_URL") ?: "jdbc:postgresql://localhost:5432/opslab"
    user = System.getenv("SPRING_DATASOURCE_USERNAME") ?: "inter"
    password = System.getenv("SPRING_DATASOURCE_PASSWORD") ?: "1234"
    schemas = arrayOf("opslab")
    defaultSchema = "opslab"
    locations = arrayOf("classpath:db/migration")
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