plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    jacoco
}

// ★ board-service 는 자체 DB(lemuel_board) 를 소유하는 DB-per-service 독립 부팅 서비스.
//   메타 주도 게시판 플랫폼 — board_definitions 1행이 게시판 1개이고, 프론트의 단일 라우트
//   /boards/:boardKey 가 정의를 읽어 스킨을 바꿔 그린다. 배포 없이 게시판을 만들 수 있다.
//
//   ★ 발행 0 · 소비 0: 다른 서비스와의 연계가 없다(docs/plan/board-service.md §3).
//   - 게시판별 권한은 RBAC permission 코드가 아니라 역할 allowlist → order DB 조회 불필요
//   - 작성자 표시명은 작성 시점 스냅샷 → user 프로젝션 불필요
//   - 메뉴 등록은 관리자가 기존 POST /admin/menus 를 호출 → cross-service write 없음
//   그래서 kafka·outbox 의존을 넣지 않는다. 넣는 순간 "쓰지 않는 배선"이 생기고, 다음 사람은
//   그걸 보고 이 서비스가 이벤트를 발행한다고 오해한다.

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
    // adapter in/out 서브패키지는 통합 테스트로 별도 검증 — 커버리지 게이트 제외(전 모듈 공통 기준)
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "github/lms/lemuel/board/adapter/**",
                    "github/lms/lemuel/board/config/**"
                )
            }
        })
    )
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
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "github/lms/lemuel/board/adapter/**",
                    "github/lms/lemuel/board/config/**"
                )
            }
        })
    )
}
