plugins {
    `java-library`
}

dependencies {
    // Spring 코어 (라이브러리 모듈이라 starter 가 아닌 개별 컴포넌트)
    api("org.springframework:spring-context")
    api("org.springframework:spring-web")
    api("jakarta.servlet:jakarta.servlet-api")
    api("jakarta.validation:jakarta.validation-api")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.slf4j:slf4j-api")

    // JWT (gateway/order/settlement 공통)
    api("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // 구조화 로그 + Rate limiting 공통
    api("net.logstash.logback:logstash-logback-encoder:7.4")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
