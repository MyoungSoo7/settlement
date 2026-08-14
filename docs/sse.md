# SSE (Server-Sent Events) 정본

폴리글랏 7종 중 **gateway 에 라우팅되는 2종만** 브라우저에 스트림을 연다. 나머지 5종은 gateway
미라우팅이다(정본: `polyglot-services.md`). 이 문서는 그 2개 스트림의 **계약·인증·재생·한계**를
한 곳에 모은다 — 코드 주석이 `docs/sse.md` 를 가리키는 대상이 여기다.

| 스트림 | gateway 경로 | 서비스 실경로 | 서비스 | 인증 | 재생(Last-Event-ID) |
| --- | --- | --- | --- | --- | --- |
| 실시간 시세 | `/api/market-stream/**` | `/stream/{stockCode}` | market-stream (Go, 8110) | 공개 | **없음(라이브 전용)** |
| 알림 푸시 | `/api/notifications/stream` | `/notifications/stream` | notification (Kotlin, 8130) | JWT 필수 | 있음(보존 창 안에서) |

gateway 는 두 경로 모두 프리픽스를 벗겨 전달한다(`RewritePath`). 알림 쪽은 **와일드카드를 쓰지 않는다** —
`/notifications/send`·`/notifications/demo` 는 인증 없이 발송하는 내부 경로라 노출 대상이 아니다.

## 1. 시세 스트림 (market-stream)

프레임은 `event: tick` + JSON 한 줄이다. 연결 즉시 `: connected` 주석을 흘려 클라이언트가
`onopen` 을 바로 보게 한다.

- **한 HTTP 요청 == 한 Hub 구독**. 요청 컨텍스트가 취소되면 구독을 해제하고, 마지막 구독이었으면
  해당 종목의 시세 루프까지 멈춘다(고루틴 누수 0).
- **`id:` 프레임을 보내지 않는다** — 따라서 재접속 시 재생도 없다. 시세는 최신값만 의미가 있어
  놓친 틱을 되돌려 줄 이유가 없다는 판단이다. `Last-Event-ID` 를 보내도 무시된다.
- `X-Accel-Buffering: no` 를 직접 세팅한다 — 프록시가 버퍼링하면 틱이 묶여서 도착한다.

## 2. 알림 푸시 (notification)

한 HTTP 연결 == 한 허브 구독이고, 수신자 키는 **JWT 에서만** 파생한다. 경로·쿼리로 받은 식별자를
믿으면 그 순간 푸시 스트림이 IDOR 이 된다.

- 라우팅 키: `sub`(이메일) · `uid`(셀러 id) · **ADMIN 에 한해** ops 메일함
  (`NotificationTemplate.OPS_FALLBACK_RECIPIENT`). 수신자 필드가 없는 이벤트가 조용히 사라지지 않고
  ops 로 모이게 하려는 의도적 fail-visible 기본값이다.
- **토큰을 쿼리 파라미터로도 받는다**: 브라우저 `EventSource` 는 요청 헤더를 설정할 수 없어
  쿼리가 유일한 인증 수단이다. 둘 다 오면 헤더가 이긴다. URL 토큰은 액세스 로그에 남을 수 있으니
  비브라우저 클라이언트는 반드시 헤더를 쓴다.
- **재생**: 재접속 시 브라우저가 `Last-Event-ID` 를 자동으로 재전송하고, 허브가 보존 창 안에서
  놓친 이벤트를 다시 보낸다. 망가진 재개 지점(음수·비정수)은 요청을 실패시키지 않고 "라이브 전용"으로
  degrade 한다 — 잘못 저장된 id 하나로 클라이언트가 영구히 에러 루프에 빠지지 않게.
- **하트비트**: 15초마다 주석 프레임. 프록시는 1분 침묵한 연결을 예사로 끊고, 죽은 피어는 그러지
  않으면 다음 실제 이벤트에서야 발견된다. 연결 직후 `retry` 힌트(2s)를 보내 재접속 백오프를 고정한다.
- **실패는 fail-closed**: 서명키 미설정이면 `503 STREAM_NOT_CONFIGURED`, 신원 확인 실패는 `401`.
  키가 없다고 "묻는 사람을 믿는" 폴백은 없다. 단 서비스는 키 없이도 기동한다 — Kafka·REST 경로는
  키가 필요 없고, 스트림만 닫힌다.

### 보존 한도 (`app.stream.*`)

프로세스 메모리라 무한히 자라지 않게 3중으로 묶는다.

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `app.stream.buffer-per-recipient` | 100 | 수신자별 재생 창 |
| `app.stream.max-recipients` | 10000 | 유휴 수신자부터 정리 |
| `app.stream.max-pending-per-subscriber` | 200 | 구독자별 미전달 큐 |

### MVP 한계 (의도된 것)

상태가 **프로세스 로컬**이다. 재시작하면 재생 창이 사라지고, 레플리카가 둘 이상이면 클라이언트는
재접속한 레플리카가 마침 들고 있는 것까지만 재개한다. 공유 저장소로 옮기는 것은 후속 과제다.

## 3. 프록시 주의

SSE 는 프록시 설정 하나로 조용히 죽는다 — 버퍼링이 켜져 있으면 이벤트가 묶이고,
`proxy_read_timeout` 이 짧으면 유휴 연결이 잘린다.

`frontend/nginx.conf`·`nginx.compose.conf` 는 **두 스트림 모두** 전용 location 을 두어
`proxy_buffering off` + `proxy_cache off` + `proxy_read_timeout 3600s` 를 건다.

| 경로 | 매칭 | 비고 |
| --- | --- | --- |
| `/api/market-stream/` | 프리픽스(`^~`) | `/stream/{code}` 다수 종목 |
| `/api/notifications/stream` | **정확 일치(`=`)** | gateway 도 와일드카드를 금지한다 — `/notifications/send`·`demo` 는 인증 없이 발송하는 내부 경로라 노출 대상이 아니다. 프리픽스로 열면 그 결정을 프록시 층에서 되돌리는 셈이다. 쿼리스트링(`?token=`)은 location 매칭에 영향을 주지 않아 EventSource 인증 경로도 그대로 탄다 |

전용 location 이 없으면 일반 `api` location 으로 떨어져 버퍼링이 켜진 채 `proxy_read_timeout 60s`
를 받는다. **gateway 직결(개발)에서는 정상 동작하고 nginx 를 앞에 둔 compose·배포 경로에서만
깨지는** 형태라 눈에 잘 띄지 않는다 — 실제로 알림 스트림이 한동안 그 상태였다.

실측(스텁 업스트림이 2초 간격으로 3틱 전송): 전용 location 은 2초 간격 그대로 도착하고,
일반 `api` location 은 스트림이 끝난 뒤 세 틱이 한꺼번에 도착한다.
