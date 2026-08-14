# Seed — notification-service 알림 팬아웃·SSE 푸시 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`notification-service-fanout.seed.yaml`](notification-service-fanout.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**notification-service(Kotlin 폴리글랏 8130 — 5토픽 구독·채널 4종 동시 팬아웃·eventId TTL 멱등·DLT 격리·
JWT 파생 수신자 SSE 푸시)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                        | 제외                                |
| ------------------------------------------- | ----------------------------------- |
| Kafka 인바운드 (5토픽·eventId·삼키지 않음)  | 발행측 서비스의 이벤트 계약 정의    |
| 에러 핸들링 (재시도·즉시 DLT·파티션 -1)     | 실 SMTP/Slack 운영 검증             |
| 디스패치 코어 (멱등·코루틴 팬아웃·재시도)   |                                     |
| 채널 4종 · SSE 푸시(신원·재개·재생 창)      |                                     |
| 배포 형태 (JDK21·non-root·게이트웨이 배선)  |                                     |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **삼키지 않는다** — 파싱 실패는 `UnparseableEventPayloadException`, 전 채널 실패는 `NotificationDispatchFailedException` 으로 던진다. 잡으면 오프셋이 커밋되고 메시지가 소멸 (`DomainEventListener.kt:16-34,59-105`).
2. **세 가지 비대칭이 의도** — 부분 성공은 안 던짐(replay 중복 방지) / `deduped` 는 실패 아님 / 활성 채널 0은 배포 오류라 스트림 전량을 DLT 로 밀지 않음.
3. **재시도가 못 고치는 건 즉시 격리** — `notRetryable`(Json·IAE·ISE), 그 외 `FixedBackOff(2s×3)` (`KafkaErrorHandlingConfig.kt:126-146`).
4. **DLT 파티션은 프로듀서가 고른다(-1)** — 소스 파티션 고정 시 DLT 파티션 부족으로 격리 자체가 실패(실측 6 vs 3). key 보존으로 키별 순서는 유지.
5. **`AckMode.RECORD`** — 리스너에 `Acknowledgment` 파라미터가 없어 MANUAL 은 영구 미커밋이 된다.
6. **eventId 는 헤더 우선** — kafka key 를 1순위로 쓰면 같은 aggregateId 의 두 번째 이벤트가 dedupe 로 사라진다 (`:82`).
7. **푸시는 요청을 안 믿는다** — 수신자 키 = `sub` + `uid` + (ADMIN 만) ops 메일함. 파라미터 유래 0 (`JwtSubscriberIdentityResolver.kt:99-104`).
8. **fail-closed** — 서명키 미설정/32byte 미만이면 스트림 503, 서비스는 계속 기동.
9. **재개는 무결(gapless)** — 구독 등록과 백로그 적재가 한 락 안. 리스너 호출은 락 밖이라 느린 브라우저가 발행을 막지 못한다 (`InMemoryNotificationStream.kt:76-101,158-172`).
10. **도메인 봉인** — `Notification.init` 이 blank 거부(`copy()` 도 주 생성자 경유), `StreamEvent.seq >= 1`·전역 단조.

## 이벤트 계약

| 방향 | 토픽 |
|------|------|
| 소비 | `lemuel.settlement.confirmed` · `lemuel.payment.confirmed` · `lemuel.payment.captured` · `lemuel.payment.refunded` · `lemuel.investment.executed` |
| 발행 | **없음** (`<topic>.DLT` 격리 복사만) |

> shared-common 미의존이라 ADR 0024 양방향 계약 테스트 대상이 아니다 — 드리프트는 런타임 파싱 실패로만 드러난다.

## 수용 기준 (게이트 매핑)

| AC   | 기준                                    | 게이트                                                     |
| ---- | --------------------------------------- | ---------------------------------------------------------- |
| AC-1 | 템플릿 분류·수신자/금액 폴백 일치       | `NotificationTemplateTest`                                 |
| AC-2 | 멱등·팬아웃·타임아웃/재시도 일치        | `NotificationDispatcherTest`·`DedupeStoreTest`             |
| AC-3 | 유독·전채널실패가 DLT 로 격리           | `DomainEventListenerTest`·`KafkaErrorHandlingConfigTest`·`DlqEndToEndTest`(EmbeddedKafka) |
| AC-4 | SSE 신원·401·503 일치                   | `JwtSubscriberIdentityResolverTest`·`NotificationStream*Test` |
| AC-5 | 재개·순서·재생 창 상한 일치             | `InMemoryNotificationStreamTest`                           |
| AC-6 | 전체 빌드 GREEN (63 tests)              | `JAVA_HOME=<JDK21> ./gradlew build` · `polyglot-ci.yml`    |

## Known Issues (발견만 기록)

- **KI-1 ★high**: `app.security.jwt.secret` 이 `application.yml` 에 **없다** → SSE 스트림이 항상 503. 기능 전체가 꺼진 상태.
- **KI-2 ★high**: compose 에 컨테이너 정의 없음 + gateway 에 `NOTIFICATION_SERVICE_URI` 없음 → 게이트웨이가 자기 자신으로 프록시.
- **KI-3 ★high**: nginx 무버퍼 location 부재 → SSE 버퍼링 + 60초 절단.
- **KI-4**: `app.kafka.enabled` 기본 false + 대체 입력 REST 는 게이트웨이 미노출 → 기본 구성에선 입출력이 모두 없다.
- **KI-5**: `/notifications/send`·`/demo` 무인증 — 방어가 "라우트 미등록" 하나뿐.
- **KI-6**: dedupe·재생 창 휘발성 — 재시작 시 중복 발송, 재개 강등. 회계 영향은 0(하류 `processed_events`).
- **KI-7 (해소)**: `../../study/sse.md` dangling 참조 3곳(KDoc 2 + CLAUDE.md)은 2026-08-13 병행 세션이 문서를 추가해 해소. 단 그 문서는 KI-1~KI-3(시크릿·배선)을 다루지 않는다.
- **KI-8**: Email/Slack 활성 경로 미검증(주석 명문).
- **KI-9**: 부분 실패 채널에 재전송 경로·메트릭 없음.
- **KI-10**: 계약 테스트 미참여 — 페이로드 드리프트가 빌드에서 안 잡힌다.
