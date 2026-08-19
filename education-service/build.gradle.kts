plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    jacoco
}

dependencies {
    implementation("github.lms.lemuel:shared-common:1.0.0")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("io.github.cdimascio:java-dotenv:5.2.2")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jackson-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ⚠️ classDirectories(측정 대상)는 루트 build.gradle.kts 가 단독 소유한다 — 여기서 다시 얹지 말 것.
// classDirectories.files 는 설정 시점에 즉시 평가되므로 루트가 이미 교체한 값 위에 같은 관용구를
// 한 번 더 얹으면 클린 빌드(=CI 의 `clean :module:build`)에서 빈 집합이 스냅샷되어 측정 대상 0개로
// 게이트가 공전한다. 제외 패턴이 필요하면 루트의 목록에 추가한다.
