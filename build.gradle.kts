import org.flywaydb.gradle.FlywayExtension

plugins {
    java
    id("org.springframework.boot") version "4.0.4"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("org.sonarqube") version "5.1.0.4882"
    id("io.snyk.gradle.plugin.snykplugin") version "0.6.1"
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
    runtimeOnly("org.postgresql:postgresql:42.7.3")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.0")

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
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Mockito 를 명시적 javaagent 로 주입 (JDK 21+ 필수, JDK 25 에서 self-attach 불가)
    // 동시에 JaCoCo 에이전트와의 충돌을 방지해 instrumentation 이 정상 적용되도록 한다
    testImplementation("org.mockito:mockito-core")
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
            // 2026-04-22: Mockito self-attach 문제 수정 후 실제 커버리지는 약 14% (이전 측정치 1% 는 JaCoCo
            // instrumentation 이 소실돼 잘못 잡힌 수치). 현재를 회귀 방지선으로 잡고 CLAUDE.md 목표(70%)
            // 까지 점진적으로 끌어올린다.
            // TODO(70% 달성 경로): category 서비스·product 서비스·settlement 배치 서비스·각 어댑터에 테스트 추가.
            limit {
                minimum = "0.13".toBigDecimal()
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
snyk {
    // Snyk 설정
    setArguments("--all-projects")
    setSeverity("low") // low, medium, high, critical
    setApi(System.getenv("SNYK_TOKEN") ?: "")
    setAutoDownload(true)
    setAutoUpdate(true)
}

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