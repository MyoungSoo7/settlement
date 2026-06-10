plugins {
    `java-library`
    id("io.spring.dependency-management")
}

dependencies {
    // Spring Boot 의 BOM 을 사용하기 위해 dependency-management 만 적용 (boot plugin 자체는 X — 라이브러리 모듈)
    api(platform("org.springframework.boot:spring-boot-dependencies:4.0.4"))

    // Spring 코어
    implementation("org.springframework:spring-aop")
    implementation("org.aspectj:aspectjweaver")
    api("org.springframework:spring-context")
    api("org.springframework:spring-web")
    api("org.springframework:spring-tx")
    api("jakarta.servlet:jakarta.servlet-api")
    api("jakarta.validation:jakarta.validation-api")
    api("jakarta.persistence:jakarta.persistence-api")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.slf4j:slf4j-api")

    // JPA (audit, outbox, processed-events 가 JpaEntity 사용)
    api("org.springframework.data:spring-data-jpa")
    api("org.hibernate.orm:hibernate-core")

    // Kafka (outbox publisher 용)
    api("org.springframework.kafka:spring-kafka")

    // JWT (양 서비스 + gateway 공통)
    api("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // 구조화 로그
    api("net.logstash.logback:logstash-logback-encoder:7.4")

    // Rate limiting
    api("com.bucket4j:bucket4j-core:8.10.1")

    // PDF (common/pdf 가 iText 사용 시)
    api("com.itextpdf:itext-core:8.0.5")
    api("com.itextpdf:font-asian:8.0.5")

    // SpringDoc (OpenApiConfig 등에서 사용)
    api("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Spring Security (SecurityConfig 등)
    api("org.springframework.security:spring-security-config")
    api("org.springframework.security:spring-security-web")
    api("org.springframework.security:spring-security-crypto")

    // Micrometer (outbox publisher 메트릭 등)
    api("io.micrometer:micrometer-core")

    // 분산 트레이싱 — Micrometer Tracing + OpenTelemetry Bridge.
    // OutboxEvent 가 traceparent 를 영속화해 비동기 경계에서도 trace context 가 끊기지 않게 한다.
    api("io.micrometer:micrometer-tracing")
    api("io.micrometer:micrometer-tracing-bridge-otel")
    api("io.opentelemetry:opentelemetry-exporter-otlp")

    // Spring Boot autoconfigure (ConditionalOnProperty 등 어노테이션 사용)
    api("org.springframework.boot:spring-boot-autoconfigure")

    // Cache (Caffeine)
    api("org.springframework:spring-context-support")
    api("com.github.ben-manes.caffeine:caffeine")

    // HikariCP — read-replica 라우팅 데이터소스(ReadReplicaDataSourceConfig)의 컴파일 전용 타입.
    // 런타임 구현은 각 앱 모듈의 spring-boot-starter-data-jpa 가 전이 제공한다.
    compileOnly("com.zaxxer:HikariCP")

    // ShedLock — @Scheduled 의 분산 락 (replicas N 개 중 1 개만 실행 보장)
    api("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    api("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    // QueryDSL (config bean)
    api("com.querydsl:querydsl-jpa:5.0.0:jakarta")

    // Lombok (JDK 25 지원 — 1.18.40+ 필요)
    compileOnly("org.projectlombok:lombok:1.18.40")
    annotationProcessor("org.projectlombok:lombok:1.18.40")
    testCompileOnly("org.projectlombok:lombok:1.18.40")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.40")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.postgresql:postgresql:42.7.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
