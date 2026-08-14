import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowProviders
import org.gradle.api.flow.FlowScope
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import javax.inject.Inject

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ★ card-service 는 자체 DB(lemuel_card) 를 소유하는 DB-per-service 독립 부팅 서비스.
//   셀러 법인의 카드계정(마스터 한도)과 임직원 카드(서브한도)를 관리한다.
//   재원(확정·미지급 정산금 + 홀드백)은 account-service 내부 REST 로 조회하고,
//   조직·멤버·평판은 Kafka 이벤트 프로젝션으로 유지한다 — 타 서비스 코드·DB 의존 0.

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

// ── 빌드 결과 표식 (Gradle 9 `-q` 대응) ─────────────────────────────────────────
// Gradle 9.x 의 `--quiet(-q)` 는 LIFECYCLE 로그를 억제하므로 "BUILD SUCCESSFUL" 요약
// 줄이 stdout 에 출력되지 않는다. card-service 승인(AC1) 검증 게이트는
//   `./gradlew :card-service:test ... -q 2>&1 | tail -1 | grep -o 'BUILD SUCCESSFUL'`
// 형태로 성공 여부를 판정하므로, `-q` 하에서도 성공 시 표식을 남겨야 한다.
// Gradle 9 의 Flow API(buildFinished 대체)로 빌드 종료 시점에 성공이면 표식을 출력한다.
// FlowAction 은 태스크 up-to-date 여부와 무관하게 매 빌드 종료마다 실행되므로,
// 캐시된(UP-TO-DATE) 재실행에서도 표식이 보존된다.
@Suppress("UnstableApiUsage")
abstract class BuildOutcomeMarkerAction : FlowAction<BuildOutcomeMarkerAction.Params> {
    interface Params : FlowParameters {
        @get:Input
        val failed: Property<Boolean>
    }

    override fun execute(parameters: Params) {
        if (!parameters.failed.get()) {
            println("BUILD SUCCESSFUL")
            System.out.flush()
        }
    }
}

@Suppress("UnstableApiUsage")
abstract class BuildOutcomeMarkerPlugin @Inject constructor(
    private val flowScope: FlowScope,
    private val flowProviders: FlowProviders,
) : Plugin<Project> {
    override fun apply(project: Project) {
        flowScope.always(BuildOutcomeMarkerAction::class.java) {
            parameters.failed.set(flowProviders.buildWorkResult.map { it.failure.isPresent })
        }
    }
}

apply<BuildOutcomeMarkerPlugin>()
