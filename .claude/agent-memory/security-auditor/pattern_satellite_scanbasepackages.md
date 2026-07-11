---
name: pattern_satellite_scanbasepackages
description: 위성 MSA(economics/financial/company/market/ai 등)가 shared-common을 얼마나 스캔하는지에 따라 보안 체인이 달라지는 구조 — 감사 시 확인 절차
metadata:
  type: pattern
---

이 레포(settlement 모노레포)의 위성 서비스들은 두 갈래로 나뉜다:

1. **shared-common 완전 미의존** (economics/financial/company/market-service) —
   `build.gradle.kts` 에 shared-common 의존성 자체가 없고, 자체 최소 `SecurityConfig` 를
   직접 작성한다 (CLAUDE.md에 "★ shared-common 미의존" 으로 명시). 이 경우 보안 체인은
   그 서비스 파일 하나만 읽으면 전체 그림이 나온다.

2. **shared-common 제한 스캔** (ai-service, operation-service 유사) —
   `@SpringBootApplication(scanBasePackages = {..., "github.lms.lemuel.common.config.jwt"})`
   처럼 JWT 스택만 콕 집어 스캔한다. 이 경우 **그 패키지 안에 있는 모든 `@Configuration`
   빈이 함께 딸려온다** — `JwtAuthenticationFilter` 뿐 아니라 `SecurityConfig`
   (전역 시큐리티 체인, `@EnableWebSecurity`), `InternalApiKeyFilter` 등도 전부 포함.

**감사 절차**: 새 서비스를 감사할 때 `scanBasePackages` 값을 확인하고,
`common.config.jwt` 가 포함되어 있으면 반드시 `shared-common/src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`
를 함께 읽어서 그 서비스에 실제로 적용되는 `authorizeHttpRequests` 규칙을 확인할 것.
그 파일은 order-service 전용 라우팅 규칙(정산/쿠폰/환불 등)이 잔뜩 섞여 있어 위성
서비스에는 대부분 무관하지만, `permitAll` 로 열린 공통 경로(`/actuator/health`,
`/actuator/prometheus`, `/swagger-ui/**`, `/v3/api-docs/**`, `anyRequest().authenticated()`
catch-all)는 위성 서비스에도 그대로 적용된다.

[[project_ai_service_security]] 가 이 패턴을 적용한 첫 사례.
