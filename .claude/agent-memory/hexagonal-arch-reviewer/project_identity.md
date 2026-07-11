---
name: project-identity
description: settlement 리포는 Java25/Spring Boot4 Gradle 멀티모듈 MSA — 시스템 프롬프트의 React/TS/inter/lemuel frontend 서술은 이 프로젝트와 무관
metadata:
  type: project
---

이 에이전트(hexagonal-arch-reviewer)의 시스템 프롬프트는 "inter/lemuel" React 18 + TypeScript + Vite 프론트엔드를 검수 대상으로 서술하지만, 실제 작업 디렉터리(`C:\Users\iamip\IdeaProjects\kubenetis\settlement`)는 **Java 25 + Spring Boot 4 + Gradle Kotlin DSL 멀티모듈 백엔드 MSA**(order/settlement/loan/financial/economics/company/operation/market/ai/gateway 9+1 서비스)다.

**Why:** 시스템 프롬프트가 다른 프로젝트 템플릿에서 온 것으로 보이며 실제 CLAUDE.md(프로젝트 루트)가 정본이다.

**How to apply:** 검수 시 TypeScript/React 컨벤션(Vitest, Axios, src/api 등)은 절대 적용하지 말 것. 대신 프로젝트 CLAUDE.md 의 "헥사고날 아키텍처 (각 서비스 내부)" 섹션과 각 서비스의 `{Service}ArchitectureTest.java`(ArchUnit)를 기준으로 삼는다. 계층 경로도 `domain/`, `application/port/{in,out}`, `application/service/`, `adapter/{in,out}/*` (Java 패키지) 기준이지 `src/types`, `src/api`, `src/components` 가 아니다.

경계 컨텍스트는 settlement/order/payment 3자가 아니라 **order-service 내부의 payment 서브도메인 + settlement-service + loan/financial/economics/company/operation/market/ai 등 독립 MSA**로 구성됨. cross-service 의존은 Kafka 이벤트(Outbox) 또는 내부 REST(`/internal/**`, `X-Internal-Api-Key`)로만 이뤄지고 코드 import 는 0이어야 한다(각 서비스 build.gradle.kts 에 타 서비스 project 의존 없음).
