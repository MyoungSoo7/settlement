plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ market-service 는 KRX(한국거래소) 상장사 일별 시세·시가총액을 공공데이터포털
//   금융위원회 주식시세정보(getStockPriceInfo) 로 수집해 제공하는 공개 read-only 조회 서비스.
//   자체 DB(lemuel_market) 소유 + 독립 부팅. financial(DART 재무제표)·economics(ECOS 지표)와
//   형제 위성이며, stockCode(6자리 단축코드)를 financial/company 와 공용 비즈니스 키로 쓴다.
//   시세만 서빙하고 PER/PBR 은 계산하지 않는다 — financial 을 import/DB조인하면 MSA 경계가
//   깨지므로 밸류에이션 조인은 소비측(CEO 브리핑 프론트/invest-copilot)이 두 서비스의 공개
//   GET 을 각각 호출해 합친다. 회원/주문 컨텍스트·Kafka 이벤트와 무관하므로 shared-common
//   (JWT·Outbox·Kafka 토글)을 의도적으로 물지 않는다 — 자체 최소 SecurityConfig 보유.

dependencies {
    // Spring Boot 스타터
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // Flyway — 자체 DB(lemuel_market) 마이그레이션을 직접 책임진다.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")

    // SpringDoc OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Caffeine (종목 카탈로그/시계열 조회 캐시)
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
