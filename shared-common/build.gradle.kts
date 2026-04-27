plugins {
    `java-library`
    id("io.spring.dependency-management")
}

dependencies {
    // Spring Boot 의 BOM 을 사용하기 위해 dependency-management 만 적용 (boot plugin 자체는 X — 라이브러리 모듈)
    api(platform("org.springframework.boot:spring-boot-dependencies:4.0.4"))

    // Spring 코어
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

    // Spring Boot autoconfigure (ConditionalOnProperty 등 어노테이션 사용)
    api("org.springframework.boot:spring-boot-autoconfigure")

    // Cache (Caffeine)
    api("org.springframework:spring-context-support")
    api("com.github.ben-manes.caffeine:caffeine")

    // QueryDSL (config bean)
    api("com.querydsl:querydsl-jpa:5.0.0:jakarta")

    // Lombok (JDK 25 지원 — 1.18.40+ 필요)
    compileOnly("org.projectlombok:lombok:1.18.40")
    annotationProcessor("org.projectlombok:lombok:1.18.40")
    testCompileOnly("org.projectlombok:lombok:1.18.40")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.40")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
