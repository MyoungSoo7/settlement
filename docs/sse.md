# 실시간 스트리밍 (SSE) — 계약·재연결·푸시 허브

이 플랫폼의 서버→클라이언트 실시간 경로 **정본**. WebSocket/STOMP 브로커나 사용자 간 채팅은 없다 —
전부 **단방향 SSE**(+ market-stream 의 부가 WebSocket)이며, 양방향이 필요한 유일한 지점(AI 챗봇)도
요청은 평범한 POST, 응답만 스트림이다.

| 스트림 | 서비스 | 경로(프론트 계약) | 인증 | 재연결 복구 |
|---|---|---|---|---|
| 실시간 시세 | market-stream-service (Go, 8110) | `GET /api/market-stream/stream/{code}` | 없음(공개 read-only) | ✅ `Last-Event-ID` |
| 알림 푸시 | notification-service (Kotlin, 8130) | `GET /api/notifications/stream` | JWT 필수 | ✅ `Last-Event-ID` |
| AI 챗봇 토큰 | ai-service (8096) | `POST /api/ai/chat/stream` | JWT 필수 | ❌ (설계상 불필요 — 아래 참조) |
| 시세 WebSocket | market-stream-service | `GET /ws/{code}`(게이트웨이 미노출) | 없음 | ✅ `?lastEventId=` |

---

## 1. 공통 프레임 계약

```
: connected
retry: 2000

id: 42
event: tick
data: {"stockCode":"005930","price":71500.5,"ts":"2026-08-11T00:00:01.000Z","seq":42}

```

- `id:` = **재개 지점**. 브라우저 `EventSource` 는 마지막으로 본 `id` 를 기억했다가 재연결 시
  `Last-Event-ID` 헤더로 자동 재전송한다. 서버는 그 값 **초과** 시퀀스만 재생한다.
- `retry:` = 재연결 백오프 힌트(ms). 없으면 브라우저 기본값(Chrome ≈3s)이라 재시작 후 전 대시보드가
  같은 순간에 몰려 재접속한다.
- `event:` = 이벤트 이름(`tick` / `notification`). 클라이언트는 `addEventListener(name)` 으로 받는다.
  콜론 뒤 공백은 SSE 스펙상 선택 — 파서/테스트가 공백을 강제하면 안 된다.
- 첫 바이트(`: connected` 주석)를 즉시 흘려보낸다. `EventSource.onopen` 은 바이트가 도착해야 뜬다.

### 시퀀스 번호 규칙

| | market-stream | notification |
|---|---|---|
| 범위 | **종목코드별** | **전역**(수신자별 아님) |
| 시작 | 1 | 1 |
| 보관 | 코드당 최근 64틱(`MARKET_STREAM_REPLAY_CAPACITY`) | 수신자당 최근 100건(`APP_STREAM_BUFFER`) |

알림 허브가 전역 시퀀스를 쓰는 이유: 한 클라이언트가 **여러 신원**(userId·이메일·ops 사서함)으로
동시에 구독하므로, 신원별 번호를 쓰면 한 연결 위에서 id 가 뒤섞여 재개 지점이 무너진다.

---

## 2. 재연결 복구가 실제로 하는 일

```
클라이언트                     서버
   |-- GET /stream ----------->|  id:1, id:2, id:3 …
   |   (네트워크 끊김)          |  (그동안 발생한 이벤트는 보관 링버퍼에 적재)
   |-- GET /stream ----------->|  Last-Event-ID: 3
   |<- id:4, id:5 (재생) ------|  그 후 라이브로 전환
```

구현상 중요한 두 가지:

1. **구독 등록과 백로그 스냅샷은 같은 락 안에서** 이루어진다. 그렇지 않으면 그 사이에 발행된
   이벤트가 백로그에도 없고 라이브에도 없는 **구멍**이 되거나, 양쪽에 들어가 **중복**된다.
   - Go: `hub.SubscribeFrom()` — 백로그를 구독 채널에 먼저 적재한 뒤 등록(채널 버퍼는 백로그 크기 이상).
   - Kotlin: `InMemoryNotificationStream.subscribe()` — 백로그를 구독자 메일박스에 넣고 등록까지 한 락.
2. **시세 스트림의 시퀀스는 구독자가 0이 되어도 리셋되지 않는다.** 마지막 구독자가 떠나면 quote 루프는
   멈추지만(고루틴 누수 0), 시퀀스·링버퍼를 담은 `streamState` 는 살아남는다. 리셋하면 재접속한
   클라이언트의 저장된 `Last-Event-ID` 가 새 스트림보다 한참 앞서서, 조용히 아무것도 못 받는다.
   유휴 코드가 512개를 넘으면 **구독자 없는** 것부터 오래된 순으로 정리한다.

### 재생되지 않는 것

- 보관 창을 넘긴 이벤트(시세 64틱 / 알림 100건). 클라이언트는 **id 가 건너뛴 것으로** 인지할 수 있다
  — 서버는 "최신입니다"라고 거짓말하지 않는다.
- **프로세스 재시작 이후**. 두 허브 모두 인메모리다.
- **다른 레플리카에서 발생한 이벤트**. 재접속한 인스턴스가 가진 것만 재생된다(§6 한계).
- 느린 클라이언트 때문에 버려진 이벤트: 시세는 drop-oldest(최신 우선), 알림은 메일박스 200건 초과 시
  가장 오래된 것부터 버리며 **경고 로그**를 남긴다.

### AI 챗봇 스트림에 재연결 복구가 없는 이유

`POST /api/ai/chat/stream` 은 한 번의 요청-응답 왕복을 토큰 단위로 흘려보내는 것이라, 끊기면 재생할
"놓친 스트림"이 아니라 **미완의 왕복**이 남는다(그래서 미완 왕복은 저장도 하지 않는다). 클라이언트는
같은 메시지를 다시 보내면 된다. 또 POST 라 `EventSource` 자체를 못 쓰고 `fetch` 로 파싱한다
(`frontend/src/api/aichat.ts`).

---

## 3. 알림 푸시 허브 (notification-service)

```
Kafka 도메인 이벤트 ─┐
                     ├─→ NotificationDispatcher ─→ log / slack / email 채널
REST /notifications/send ─┘                      └─→ SsePushChannel ─→ NotificationStream ─→ 브라우저
```

- SSE 는 **또 하나의 발송 채널**(`SsePushChannel`, `name="sse"`, 항상 enabled)로 붙였다. Kafka 이벤트든
  수기 발송이든 같은 팬아웃을 타므로 알림 경로가 두 갈래로 갈라지지 않는다.
- 구독자가 없어도 publish 는 성공이다 — 이벤트는 보관 창에 들어가고, 나중에 `Last-Event-ID` 로 접속한
  클라이언트가 받아 간다.
- 하트비트: 15초마다 `: ping` 주석. 유휴 커넥션을 죽이는 프록시를 막고, 죽은 피어를 다음 실제 이벤트가
  아니라 하트비트 시점에 감지한다.
- 한 커넥션의 전송은 직렬화된다(발행 스레드와 하트비트 스레드가 같은 `SseEmitter` 에 프레임을 겹쳐 쓰면
  깨진다).

### 인가 — 신원은 오직 JWT 에서

`recipients` 는 **검증된 JWT 클레임에서만** 파생한다(요청 파라미터 신뢰 금지 = CLAUDE.md IDOR 가드레일).

| 클레임 | 매핑되는 수신자 키 | 근거 |
|---|---|---|
| `sub`(이메일) | 이메일 | REST/데모 경로가 이메일로 주소를 지정 |
| `uid` | userId 문자열 | 정본 Outbox 이벤트는 `sellerId`/`userId` 로 주소 지정 |
| `role=ADMIN` | `ops@lemuel` | 주소 파생에 실패한 이벤트의 fallback 사서함 — 운영자만 |

- **fail-closed**: `JWT_SECRET` 미설정(또는 32바이트 미만)이면 스트림은 **503 `STREAM_NOT_CONFIGURED`**.
  서비스 자체는 계속 기동한다(Kafka·REST 경로는 시크릿이 필요 없다) — "설정이 없으니 통과"는 없다.
- 토큰 없음·서명 불일치·만료 → **401**. 사유는 로그에만, 응답엔 남기지 않는다.
- 게이트웨이에는 **`/api/notifications/stream` 하나만** 올렸다. `/notifications/send`·`/demo` 는 인증 없이
  발송하는 내부 경로라 와일드카드로 열면 안 된다.

#### 토큰 전달 방식의 트레이드오프

`EventSource` 는 요청 헤더를 붙일 수 없다. 그래서 서버는 두 가지를 받는다:

1. `Authorization: Bearer <jwt>` — 헤더를 붙일 수 있는 클라이언트는 이쪽(우선순위 높음)
2. `?token=<jwt>` — 브라우저 `EventSource` 의 유일한 수단

**URL 에 실린 토큰은 액세스 로그·리퍼러·프록시 캐시에 남을 수 있다.** 운영에서는 (a) 이 경로의 쿼리
로깅을 끄거나 마스킹하고, (b) 토큰 TTL 을 짧게 유지한다. 헤더를 쓸 수 있는 클라이언트가 쿼리 방식을
쓰지 않게 하는 것도 포함이다.

---

## 4. 배선 (SSE 는 프록시 버퍼링이 곧 장애)

| 지점 | market-stream | notification |
|---|---|---|
| gateway | `Path=/api/market-stream/**` → RewritePath 로 프리픽스 제거 | `Path=/api/notifications/stream` → `/notifications/stream` |
| nginx(2종) | `location ^~ /api/market-stream/` | `location = /api/notifications/stream` |
| vite dev | `/api/market-stream` → 8110 (프리픽스 제거) | `/api/notifications` → 8130 (`/api` 제거) |
| docker-compose | `market-stream-service` | `notification-service` (+ gateway `NOTIFICATION_SERVICE_URI`) |
| 프론트 클라이언트 | `src/api/marketStream.ts` | `src/api/notificationStream.ts` |

nginx 는 두 경로 모두 `proxy_buffering off` · `proxy_cache off` · `Connection ''` ·
`proxy_read_timeout 3600s`. 버퍼링을 켜 두면 프레임이 묶여서 도착해 "실시간"이 아니게 되고, 짧은 read
timeout 은 하트비트 간격보다 짧아지는 순간 커넥션을 끊는다. 서버도 `X-Accel-Buffering: no` 를 보낸다.

> vite dev 프록시는 `/api` 보다 **먼저** 선언해야 우선 매칭된다(뒤에 두면 order-service 로 샌다).

---

## 5. 설정

| 서비스 | 환경변수 | 기본값 | 의미 |
|---|---|---|---|
| market-stream | `MARKET_STREAM_REPLAY_CAPACITY` | 64 | 코드당 재생 보관 틱 수(≈1분 @1s) |
| market-stream | `MARKET_STREAM_TICK_INTERVAL` | 1s | 틱 주기 |
| market-stream | `MARKET_STREAM_SUB_BUFFER` | 16 | 구독자 채널 버퍼(초과 시 drop-oldest) |
| notification | `JWT_SECRET` | (없음) | **미설정 시 스트림 503** |
| notification | `APP_STREAM_BUFFER` | 100 | 수신자당 재생 보관 건수 |
| notification | `APP_STREAM_MAX_RECIPIENTS` | 10000 | 보관 수신자 상한(초과 시 유휴부터 정리) |
| notification | `APP_STREAM_MAX_PENDING` | 200 | 구독자 메일박스 상한(초과 시 drop-oldest) |
| notification | `APP_STREAM_TIMEOUT_MS` | 1800000 | 커넥션 수명(30분 후 재연결 유도) |
| notification | `APP_STREAM_HEARTBEAT_SECONDS` | 15 | 하트비트 주기 |
| notification | `APP_STREAM_RECONNECT_HINT_MS` | 2000 | `retry:` 값 |

---

## 6. 알려진 한계 (의도된 MVP 경계)

- **단일 인스턴스 가정**: 두 허브 모두 상태가 프로세스 메모리다. 레플리카를 늘리면 ① 시세는 인스턴스마다
  독립된 시뮬레이션 틱이 흐르고 ② 알림은 붙은 인스턴스가 가진 것만 재생된다. 수평 확장하려면 공유 백플레인
  (Redis Stream / Kafka compacted topic)으로 보관 창을 옮겨야 한다.
- **재시작 = 보관 창 소실**. 재시작 직후 재접속한 클라이언트는 `Last-Event-ID` 를 보내도 재생받지 못하고
  라이브부터 받는다(중복은 없고, 구멍이 생긴다).
- **시세 값은 시뮬레이션**이다. base 가격만 market-service 종가를 폴링해 쓰고, 틱은 랜덤워크다
  (`internal/quote/polling.go`). 실 체결가가 아니다.
- **WebSocket(`/ws/{code}`)은 게이트웨이 미노출**이다. `InsecureSkipVerify: true` 라 Origin 검증이 없고
  인증도 없다 — 노출하려면 Origin 제한과 JWT 게이트가 먼저다.
- **알림 UI 미구현**: 클라이언트 모듈(`notificationStream.ts`)만 있고 종 아이콘/센터 컴포넌트는 없다.
- notification 은 여전히 **at-least-once**(auto-commit) 소비다. 중복 발송은 eventId 멱등(TTL 30분)이
  막지만, 그 창을 넘긴 재소비는 다시 푸시된다.

---

## 7. 확인 방법

```bash
# 시세: id 와 retry 가 붙어 나오는지
curl -N http://localhost:8110/stream/005930

# 시세: 재개 — id 3 이후만 받는다
curl -N -H "Last-Event-ID: 3" http://localhost:8110/stream/005930

# 알림: 토큰 없이 → 401, JWT_SECRET 미설정이면 → 503
curl -i http://localhost:8130/notifications/stream

# 알림: 구독(별도 셸) 후 발송해 보기
curl -N "http://localhost:8130/notifications/stream?token=$JWT"
curl -X POST http://localhost:8130/notifications/send -H 'Content-Type: application/json' \
  -d '{"type":"SETTLEMENT_CONFIRMED","recipient":"42","subject":"정산 확정","body":"본문"}'
```

테스트: `market-stream-service` → `go test ./...`(`hub_test.go` 재생/시퀀스, `server_test.go` id·retry·
`Last-Event-ID`), `notification-service` → `./gradlew test`(`InMemoryNotificationStreamTest` 재생·순서·
격리, `NotificationStreamControllerTest` 인가·푸시·재생), 프론트 → `src/__tests__/api/*Stream.test.ts`.
