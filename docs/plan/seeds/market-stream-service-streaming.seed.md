# Seed — market-stream-service 실시간 시세 스트리밍 as-is 사양

> **상태: CONFIRMED** (2026-08-13) · 정본 데이터: [`market-stream-service-streaming.seed.yaml`](market-stream-service-streaming.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**market-stream-service(Go 폴리글랏 8110 — 종목당 단일 quote loop·drop-oldest 팬아웃·결정적 랜덤워크·
market-service 종가 base 폴링·SSE 외부노출/WS 내부)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함                                      | 제외                                  |
| ----------------------------------------- | ------------------------------------- |
| Hub 팬아웃 (단일 생산자·수명·논블로킹)    | KRX 실시세 수집·시가총액(market)      |
| 가격 생성 (결정성·밴드/스텝 클램프)       | 밸류에이션·파생 지표                  |
| 실데이터 승급 (PollingSource·폴백)        |                                       |
| HTTP 표면 · 배포/노출 계약                |                                       |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **수요가 없으면 생산하지 않는다** — 첫 구독자가 loop 를 켜고, 마지막 구독자가 끈다. `sync.Once` 로 다중 해제 안전 (`hub.go:92-139`).
2. **논블로킹 팬아웃** — 락 밖에서 송신, 버퍼(16) 초과 시 **가장 오래된 틱 폐기** (`hub.go:163-192`).
3. **결정적 워크** — 시드 `seed ^ fnv64a(code)`, 밴드 ±5%·스텝 ±1% 클램프 (`simulated.go:70-102`).
4. **틱 스키마는 소스 무관** — `{stockCode, price(2자리), ts(UTC RFC3339 ms)}`.
5. **스트림은 죽지 않는다** — 폴링의 모든 실패 경로가 시뮬레이션 base 로 폴백 (`polling.go:69-77`).
6. **프록시 버퍼링 회피** — `X-Accel-Buffering: no` + nginx 전용 무버퍼 location(`read_timeout 3600s`).
7. **한 요청 = 한 구독** — `r.Context()` 취소 시 `defer unsubscribe`.
8. **경로 계약은 게이트웨이가 감춘다** — 프론트는 `/api/market-stream/stream/{code}`, 실경로는 `RewritePath` 로 변환.

## 이벤트 계약

**없음 — Kafka 의존 0.** `go.mod` 의 외부 의존은 websocket 단 하나. 서비스 간 연계는 market-service 시계열 HTTP pull 1건.

## 수용 기준 (게이트 매핑)

| AC   | 기준                                | 게이트                                     |
| ---- | ----------------------------------- | ------------------------------------------ |
| AC-1 | 구독 수명·goroutine 누수 0          | `go test ./... -race` — `hub_test.go`      |
| AC-2 | 워크 결정성·클램프 일치             | `simulated_test.go`                        |
| AC-3 | SSE 프레임/헤더/400 일치            | `server_test.go`                           |
| AC-4 | 프론트 경로 계약 유지               | `frontend/src/__tests__/api/marketStream.test.ts` |
| AC-5 | Go 빌드·vet·race GREEN              | `polyglot-ci.yml` (Go 매트릭스)            |

## Known Issues (발견만 기록)

- **KI-1 ★high**: 화면의 "실시간 시세"가 **실제 체결가가 아니다** — 실데이터는 base 하나, 틱은 난수. 응답·화면에 표식 없음.
- **KI-2 ★high**: 폴링 주기가 실질 "구독 시작 시 1회" — 루프 중 base 갱신 경로 없음(`hub.go:144`). 설정 이름과 동작 불일치.
- **KI-3**: 종목·구독자·연결 상한 전무 + 무인증 → goroutine 무제한 생성 가능.
- **KI-4**: 종목코드 미검증 — 없는 코드도 70,000원 base 로 그럴듯한 스트림.
- **KI-5**: `/metrics` 없음 — `ActiveCodes`/`SubscriberCount` 가 노출되지 않는다.
- **KI-6**: 틱 폐기가 조용하다(로그·카운터 없음).
- **KI-7**: WS `InsecureSkipVerify: true` — TODO 주석 명문, 게이트웨이 미노출이 유일 방어.
- **KI-8**: distroless 라 compose healthcheck 부재 — `depends_on` 대상이 될 수 없다.
