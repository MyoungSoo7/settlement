plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "lemuel"

include(
    "order-service",
    "settlement-service",
    "loan-service",
    "financial-statements-service",
    "economics-service",
    "company-service",
    "market-service",
    "gateway-service",
    "operation-service",
    "ai-service",
)

// shared-common 은 독립 빌드(버전드 내부 라이브러리)로 분리.
// composite build 로 합성 → 서비스가 선언한 github.lms.lemuel:shared-common:<ver> 의존을
// 로컬에서는 이 included build 로 자동 치환하고, 배포 시에는 publish 된 아티팩트로 소비한다.
includeBuild("shared-common")
