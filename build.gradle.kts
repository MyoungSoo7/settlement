plugins {
    java
    id("org.springframework.boot") version "4.0.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    jacoco
    id("org.sonarqube") version "5.1.0.4882"
}

allprojects {
    group = "github.lms.lemuel"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.0")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
        // JWT 서명키는 운영에서 env(JWT_SECRET)로만 주입한다(yaml 기본값 없음 = 미설정 시 기동 실패).
        // 테스트도 동일하게 env 로 공급해, 풀 컨텍스트(@SpringBootTest) 부팅 시 ${JWT_SECRET} 미해결
        // 실패를 막는다. 운영 배포는 반드시 강한 JWT_SECRET 을 주입할 것.
        environment("JWT_SECRET", "test-only-jwt-secret-not-for-production-0123456789")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        // jacocoTestReport 는 main 소스셋 출력(build/classes/java/main + build/resources/main)을
        // classDirectories/sourceDirectories 로 읽는다. -x test 로 test 를 건너뛰면
        // test→classes 경로가 끊겨 Gradle 9 의 implicit-dependency 검증에 걸리므로,
        // compileJava·processResources 를 aggregate 하는 classes 를 명시적으로 선언한다.
        dependsOn(tasks.named("classes"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
        // QueryDSL 생성 클래스(Q*)는 자동 생성 코드 → 커버리지 측정에서 제외 (전 모듈 공통)
        classDirectories.setFrom(classDirectories.files.map { dir ->
            fileTree(dir) { exclude("**/Q*.class") }
        })
    }

    // 커버리지 임계값. CI 에서 회귀 시 즉시 빌드 실패 → PR 차단.
    // LINE 90% 목표 (persistence adapter 는 TestContainers IT 에서 별도 검증).
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("jacocoTestReport"))

        // persistence adapter, config, mapper 는 통합 테스트 대상 → 단위 테스트 커버리지에서 제외
        classDirectories.setFrom(classDirectories.files.map { dir ->
            fileTree(dir) {
                exclude(
                    "**/adapter/out/persistence/**",
                    "**/adapter/out/readmodel/**",
                    "**/adapter/out/search/**",
                    "**/adapter/out/event/**",
                    "**/adapter/out/pdf/**",
                    "**/adapter/out/external/**",
                    "**/adapter/out/notification/**",
                    "**/adapter/out/mail/**",
                    "**/adapter/out/security/**",
                    "**/adapter/out/monitoring/**",
                    "**/adapter/out/user/**",
                    "**/adapter/out/pg/**",
                    "**/adapter/out/llm/**",
                    "**/adapter/in/web/**",
                    "**/adapter/in/kafka/**",
                    "**/adapter/in/batch/**",
                    "**/adapter/in/api/**",
                    "**/adapter/in/dto/**",
                    "**/config/**",
                    "**/util/**",
                    "**/LemuelApplication*",
                    "**/SettlementServiceApplication*",
                    "**/GatewayServiceApplication*",
                    "**/FinancialStatementsApplication*",
                    "**/CompanyServiceApplication*",
                    "**/OperationServiceApplication*",
                    "**/EconomicsApplication*",
                    "**/MarketApplication*",
                    "**/CommonDataApplication*",
                    "**/AiServiceApplication*",
                    "**/InvestmentServiceApplication*",
                    "**/AccountServiceApplication*",
                )
            }
        })

        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.90".toBigDecimal()
                }
            }
            // 핵심 도메인은 더 엄격하게
            rule {
                element = "PACKAGE"
                includes = listOf(
                    "github.lms.lemuel.payment.domain.*",
                    "github.lms.lemuel.order.domain.*",
                    "github.lms.lemuel.product.domain.*",
                    "github.lms.lemuel.cart.domain.*",
                    "github.lms.lemuel.shipping.domain.*",
                    "github.lms.lemuel.settlement.domain.*",
                    "github.lms.lemuel.pgreconciliation.domain.*",
                    "github.lms.lemuel.economics.domain.*",
                    "github.lms.lemuel.market.domain.*",
                    "github.lms.lemuel.commondata.domain.*",
                    "github.lms.lemuel.ai.chat.domain.*",
                    // common.outbox.domain 게이트는 shared-common 독립 빌드로 이관됨
                )
                limit {
                    counter = "INSTRUCTION"
                    minimum = "0.80".toBigDecimal()
                }
            }
        }
    }

    // check 가 호출되면 커버리지 검증도 같이 — CI 의 ./gradlew check 가 자동 강제
    tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
}

sonar {
    properties {
        property("sonar.projectKey", "MyoungSoo7_settlement")
        property("sonar.organization", "myoungsoo7")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.qualitygate.wait", false)
        property("sonar.java.source", "25")
    }
}
