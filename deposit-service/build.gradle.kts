plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    jacoco
}

// ★ deposit-service 는 자체 DB(lemuel_deposit) 를 소유하는 DB-per-service 독립 부팅 서비스.
//   셀러 예치금·에스크로 원장의 단일 진실원으로, hold·offset·shortfall 을 통해
//   payout 과 카드 청구의 재원 이중사용을 구조적으로 차단한다.
//   다른 서비스(settlement/card/account) 코드·DB 직접 의존 0 — Kafka 이벤트 소비만.

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")
    testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.kafka:spring-kafka")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("io.github.cdimascio:java-dotenv:5.2.2")
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.9.2")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.9.2")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

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

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    // ⚠️ classDirectories(측정 대상)는 루트 build.gradle.kts 가 단독 소유한다 — 여기서 다시 얹지 말 것.
    // classDirectories.files 는 설정 시점에 즉시 평가되므로 루트가 이미 교체한 값 위에 같은 관용구를
    // 한 번 더 얹으면 ① 클린 빌드(=CI 의 `clean :module:build`)에서 빈 집합이 스냅샷되어 측정 대상 0개로
    // 게이트가 공전하고 ② 산출물이 남은 빌드에서도 엔트리가 개별 .class 파일로 굳어 경로 제외가 무효가 된다.
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
    // 제외 목록도 루트가 소유한다(adapter in/out·config·util·*Application). 위 주석 참조.
}
