# Seed — organization-service 조직·멤버십 as-is 사양

> 상태: CONFIRMED (settlement/account seed 와 동일 방식 — 역산 결정화)
> 자매 Seed: `card-service-funding-offset`(이 서비스의 이벤트를 조직 프로젝션으로 소비하는 유일한 소비처)

## Goal (한 줄)

**organization-service(셀러/기업 조직과 멤버십 — OWNER/MANAGER/STAFF, 이벤트 발행 전용)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 계약 드리프트 게이트 ·
면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- 조직·멤버십 상태머신과 전이 강제 지점
- 역할 위계(OWNER > MANAGER > STAFF)와 마지막 OWNER 보호
- 발행 4토픽 계약 표면
- 조직 생성 시 자동 OWNER 멤버십

**제외**

- 소비측(card-service) 조직 프로젝션 상세 — 자매 Seed 담당
- 파티셔닝 운영 절차(러너 존재 사실만)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `organization-service/src/main/java/github/lms/lemuel/organization/`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **상태 전이는 도메인이 강제** — 조직·멤버십 모두 `canTransitionTo` 검사를 통과해야 전이되고, 위반은 타입 예외 | `domain/Organization.java:47-49` (`InvalidOrganizationTransitionException`) · `domain/Membership.java:58-66` (`InvalidMembershipTransitionException`) |
| 2 | **역할은 위계를 가진다** — OWNER(3) > MANAGER(2) > STAFF(1). 숫자가 권한 비교의 근거 | `domain/OrgRole.java:8-11` |
| 3 | **멤버십 4상태** — INVITED → ACTIVE → SUSPENDED ⇄ ACTIVE, REMOVED 는 종단 | `domain/MembershipStatus.java:15-19` |
| 4 | **조직 2상태** — ACTIVE ⇄ SUSPENDED | `domain/OrganizationStatus.java:12-14` |
| 5 | **마지막 OWNER 보호는 서비스 계층** — 여러 멤버십을 가로지르는 불변식이라 단일 애그리거트가 알 수 없다. 애플리케이션 서비스가 `LastOwnerException` 으로 강제한다 | `domain/Membership.java:9-10,74` |
| 6 | **OWNER 정족수 판정 술어는 도메인에** — "활성이면서 OWNER" 만 카운트한다(정지된 OWNER 는 정족수가 아니다) | `Membership.java:89-91` |
| 7 | **조직 생성자는 자동 OWNER** — 생성과 동시에 즉시 ACTIVE 멤버십이 만들어지고 `invitedBy` 는 self | `Membership.java:19,34-39` |
| 8 | **종료된 멤버십은 변경 불가** — REMOVED 상태에서 역할 변경 거부 | `Membership.java:74` |
| 9 | **발행 전용** — 소비 컨슈머가 없다. 4토픽을 Outbox 로 내보내고 card-service 가 프로젝션으로 받는다 | `adapter/out/event/OrganizationEventPublisherAdapter.java:48,62,77,90` |

## 이벤트 계약

**발행 4** (Outbox 경유)

| 이벤트 | 트리거 |
|---|---|
| `OrganizationCreated` | 조직 생성(자동 OWNER 멤버십 동반) |
| `OrganizationMemberJoined` | 초대 수락 → ACTIVE |
| `OrganizationMemberRoleChanged` | 역할 변경 |
| `OrganizationMemberRemoved` | 멤버 제거 |

**소비 0** — 이 서비스는 상류다.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 조직·멤버십 상태머신 전이표와 타입 예외가 일치한다 | `./gradlew :organization-service:test` — `OrganizationTest` · `MembershipTest` |
| AC-2 | 마지막 OWNER 를 제거·강등할 수 없다 | `MembershipCommandServiceTest` (`LastOwnerException`) |
| AC-3 | 발행 4토픽이 JSON Schema 계약과 일치한다 | `OrganizationEventContractTest` |
| AC-4 | 조직 생성이 OWNER 멤버십을 동반한다 | `OrganizationCommandServiceTest` · `OrganizationLifecycleIntegrationTest` |
| AC-5 | 헥사고날 의존 방향 위반 0 | `OrganizationArchitectureTest` |
| AC-6 | 인바운드 포트가 모두 어댑터에서 도달 가능하다 | `InboundPortReachabilityTest` |
| AC-7 | 커버리지 LINE >= 90% | `./gradlew :organization-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** **전용 `*-rules` 스킬이 없다** — 16서비스 중 organization·insurance·deposit 3곳이 스킬·라우터 배선
  공백이며 `HARNESS.md` 가 "알려진 부채"로 명시한다. 이 Seed 가 그 공백의 사실 근거를 대신 채운다.
  → `disposition: recorded-not-fixed` (문서화된 부채)
- **KI-2** 마지막 OWNER 불변식이 **서비스 계층에만** 있다(불변식 5). 도메인 단위 테스트로는 잡히지 않고
  서비스 테스트에 의존하며, 동시 요청(두 OWNER 가 동시에 서로를 제거)에서 락 전략이 없으면 둘 다 통과할 수 있다.
  이 Seed 범위에서 락 여부는 확인하지 않았다. → `disposition: recorded-not-verified` (동시성 경계)
- **KI-3** 발행 4토픽의 **소비처가 card-service 하나뿐**이다. 조직 변경이 다른 서비스(정산 권한·문서 접근 등)에
  반영되지 않으며, 그 경계가 의도인지 미배선인지 문서에 없다. → `disposition: recorded-not-verified`
- **KI-4** 조직 상태가 `SUSPENDED` 여도 **멤버십 상태는 독립**이다(불변식 3·4가 서로 연동되지 않는다).
  정지된 조직의 ACTIVE 멤버가 무엇을 할 수 있는지는 이 서비스가 아니라 소비측 판단에 맡겨져 있다.
  → `disposition: by-design` (권한 판정은 소비측)
