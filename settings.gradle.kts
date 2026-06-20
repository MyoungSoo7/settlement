plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "lemuel"

include(
    "shared-common",
    "order-service",
    "settlement-service",
    "gateway-service",
)
