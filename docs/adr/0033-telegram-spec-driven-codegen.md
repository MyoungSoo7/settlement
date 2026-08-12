# ADR 0033 — 전문(電文) 스펙 주도 코드 생성 (telegram spec-driven codegen)

- 상태: **Proposed (설계만 — 미착수)**
- 일자: 2026-08-12
- 관련: ADR 0016(payout 펌뱅킹 — 본 ADR 이 **확장**하는 대상), ADR 0024(이벤트 계약-as-code — 같은 사상의
  Kafka 편), ADR 0021(shared-common composite build — 도구/스펙을 런타임 밖에 두는 선례),
  `docs/ARCHITECTURE.md §7`(MCI·EAI·ESB·FEP 대응표), `money-safety` 스킬
- 배경: 금융권 MDD(Model Driven Development) 툴이 "전문 모델 정의 → 소스 생성"으로 하는 일을,
  현행 FEP 어댑터는 **런타임 인터프리터로 절반만** 하고 있다. 나머지 절반(스펙 외부화 + 타입 안전)을
  어디에 둘 것인가에 대한 결정.

## 컨텍스트

`settlement-service` 의 펌뱅킹 FEP 어댑터(`payout/adapter/out/firmbanking/fep/`)는 이미
**메타데이터 주도** 구조다 — 필드를 선언으로 두고 범용 인코더가 해석한다.

```java
// FepLayouts.java — 선언
public static final TelegramLayout TRANSFER_REQUEST = withBody(
        new FepField(BANK_CODE, 10, AN),
        new FepField(ACCOUNT_NO, 16, AN),
        new FepField(AMOUNT, 13, N),
        new FepField(HOLDER_NAME, 20, AN),
        new FepField(REF_ID, 20, AN));

// TelegramLayout.java — 범용 엔진 (EUC-KR 바이트 기준 정렬·패딩)
public byte[] encode(Map<String, String> values) { ... }
public Map<String, String> decode(byte[] telegram) { ... }
```

이 구조는 바이트 오프셋 계산을 사람 손에서 빼앗았다는 점에서 이미 사고 한 부류를 막는다.
그러나 MDD 가 해결하는 문제 중 **두 가지가 남아 있다**.

### 갭 1 — 스펙이 Java 소스 안에 갇혀 있다

전문 규격의 원본은 실무에서 **은행이 배포하는 전문 설계서(엑셀)** 다. 현재는 그 설계서를 사람이 읽고
`FepLayouts` 상수로 옮겨 적는다. 결과적으로

- 전문 개정 시 반드시 개발자가 Java 를 고쳐야 한다 (현업·대외계 담당자가 손댈 수 없음)
- 설계서와 코드의 일치를 보증하는 장치가 없다 — **대조는 눈으로** 한다
- "우리 시스템이 다루는 전문 목록"을 산출할 방법이 없다

### 갭 2 — `Map<String, String>` 이라 계약이 런타임까지 생존한다

```java
Map<String, String> values = Map.of("AMONT", "1000");   // 오타 — 컴파일 통과, 운영에서 폭발
```

필드명 오타는 `getOrDefault(name, "")` 에 흡수되어 **빈 값으로 조용히 인코딩**된다. 금액이 `String`
이라 `money-safety` 의 BigDecimal 강제 가드도 이 경로엔 닿지 않는다. 대외 전문은 한 번 나가면
되돌릴 수 없는 지급 지시이므로, 이 계층에 컴파일 타임 방어가 없다는 것은 비대칭이다 —
**Kafka 이벤트 계약은 ADR 0024 로 빌드 시점 강제를 받는데, 대외 전문 계약은 못 받고 있다.**

### 갭 3 — 표현력 한계 (확장 시 즉시 부딪힘)

| 항목 | 현행 | 실무 요구 |
|---|---|---|
| 필드 타입 | `AN`/`N` 2종 | 날짜(D)·시각(T)·부호付 금액(S9)·암묵 소수점(V)·한글전용(K) |
| 반복부 | 없음 | 다건 이체 = 명세 N건 반복(OCCURS) — **다건 지원 시 필수** |
| 코드값 도메인 | `String` 상수(`RESP_OK="0000"`) | 허용 코드 집합 선언 + 미지 코드 수신 시 검증 |
| 버전 | 없음 | 전문 개정 시 구·신 병존, 시행일 기준 라우팅 |
| 총길이 검증 | 계산만 | 설계서 명시 길이와 **어서트** |

## 결정

**전문 스펙을 YAML 로 외부화하고, 빌드 시점에 타입 안전 코덱을 생성한다.
런타임 마이크로서비스(`mdd-service`)는 만들지 않는다.**

### 1. 스펙 단일 출처 — `telegram-spec/` (코드 아님)

```yaml
# telegram-spec/firmbanking/0200-transfer-request.yaml
telegram: TRANSFER_REQUEST
msgType: "0200"
description: 지급이체 요청
version: 1
effectiveFrom: 2026-01-01
include: common-header            # 공통부 재사용 — 현행 withBody() 의 선언적 대응물
fields:
  - { name: BANK_CODE,   length: 10, type: AN, required: true }
  - { name: ACCOUNT_NO,  length: 16, type: AN, required: true }
  - { name: AMOUNT,      length: 13, type: N,  required: true, scale: 0, min: 1 }
  - { name: HOLDER_NAME, length: 20, type: AN, charset: EUC-KR }   # 한글 1자 = 2바이트
  - { name: REF_ID,      length: 20, type: AN, required: true, idempotencyKey: true }
totalLength: 68                   # 계산값과 불일치하면 빌드 실패
```

- 은행 설계서 ↔ 스펙 파일이 1:1 대응 — 개정 시 **YAML 만** 고친다.
- 스펙 검증은 파싱 단계에서: 필드명 중복, 길이 ≤ 0, 총길이 어서트, 알 수 없는 타입, `include` dangling.

### 2. 생성기 — `telegram-codegen` (composite build, 런타임 미탑재)

```
settlement/
├── telegram-spec/                     # 📄 스펙 (git 추적 — 설계서의 코드화)
├── telegram-codegen/                  # 🔧 composite build (shared-common 과 동급)
│   ├── parser/       # YAML → SpecModel + 스펙 검증
│   ├── generator/    # SpecModel → Java / Markdown / 픽스처
│   └── gradle-plugin/  # generateTelegramSources 태스크
└── settlement-service/
    └── build/generated/telegram/      # ⚙️ 생성물 (git 미추적)
        └── .../fep/protocol/generated/
            ├── TransferRequestTelegram.java   # record — 타입 안전 VO
            ├── TransferRequestCodec.java      # encode/decode
            └── FirmbankingCodes.java          # 응답코드 enum
```

QueryDSL 배관을 그대로 재사용한다 — `settlement-service/build.gradle.kts:111` 이 이미
`build/generated/querydsl` 를 `java.srcDir` 로 붙이고, 부모 `build.gradle.kts:96` 이
`**/Q*.class` 를 JaCoCo 에서 제외한다. 생성 소스 취급의 선례가 저장소에 존재한다.

### 3. 생성물 4종

| 생성물 | 내용 | 효과 |
|---|---|---|
| **VO record** | 필드별 타입(`BigDecimal amount`, `LocalDate transDt`) + builder | 필드명 오타·타입 오류를 **컴파일 타임** 차단 |
| **Codec** | `encode(VO) → byte[]` / `decode(byte[]) → VO` | `Map` 을 API 표면에서 제거 |
| **코드값 enum** | `RespCode.OK("0000")` 등 | 미지 코드 수신 시 명시적 실패 |
| **전문 설계서 `.md`** | 필드표·오프셋·총길이 | `docs/` 에 커밋 — 문서 드리프트 0 |
| (테스트) **픽스처** | 전문별 정상·경계 샘플 | 계약 테스트 입력 (ADR 0024 의 정본 샘플과 같은 역할) |

### 4. 런타임 엔진은 존속한다

`TelegramLayout.encode/decode` 를 **버리지 않는다.** 생성된 Codec 이 내부에서 그대로 호출한다.
검증된 EUC-KR 바이트 정렬·패딩 로직이 런타임 엔진으로 남고, 생성되는 것은 **타입 안전 껍데기**다.
기존 테스트가 그대로 회귀 가드로 작동하므로 도입 리스크가 가장 낮은 경로다.

### 5. 단계 (Phase)

| Phase | 범위 | 되돌리기 |
|---|---|---|
| **1. 스펙 외부화** | YAML + 파서 + **런타임 로더**(생성 없음). `FepLayouts` 상수를 로더 결과로 대체 | 쉬움 — 상수 복원 |
| **2. 코드 생성** | VO·Codec·enum·설계서·픽스처 생성, Gradle 태스크 배선 | 중간 — 호출부가 생성 API 로 이동 |
| **3. 카탈로그·시뮬레이터** | `MockBankServer` 를 스펙 구동으로, 전문 목록·샘플 콘솔 | — (**보류**, 아래 한계 참조) |

Phase 1 만으로도 "스펙이 원본, 코드가 산출물"이라는 명제는 성립한다. Phase 2 는 타입 안전을 얻는 단계다.

## 고려한 대안

| 대안 | 기각 사유 |
|---|---|
| **(A) `mdd-service` 런타임 마이크로서비스** | ① 전문 인코딩이 원격 호출이 되어 **지급이체 핫패스에 네트워크 홉 + SPOF** 추가 — 대외계에서 최악. ② 코드 생성기는 DB 도 Kafka 도 필요 없어 "DB-per-service + 이벤트 연계"라는 서비스 정의에 맞지 않는다. ③ 서비스 로스터(16+GW)를 깨면 `harness-audit` 드리프트. ④ ADR 0021·0024 가 이미 "스펙·도구는 런타임 밖" 을 선례로 세웠다 |
| **(B) 현행 유지** | 전문 4종(0200/0210/0400/0410) 규모에서는 합리적. 다만 다건 이체(반복부)나 전문 개정이 들어오는 순간 `Map` API 와 Java 하드코딩이 동시에 무너진다 |
| **(C) 애노테이션 프로세서 (Java 선언 유지 + VO 생성)** | 타입 안전(갭 2)은 풀리지만 **스펙 외부화(갭 1)는 그대로** — 여전히 개발자만 전문을 고칠 수 있고 은행 설계서와의 대조가 눈으로 남는다 |

## 결과

### 좋아지는 점

- 대외 전문 계약이 **빌드 시점에 강제**된다 — ADR 0024 가 Kafka 에 준 보호를 FEP 경계에도 부여
- 금액이 `String` → `BigDecimal` 로 승격되어 `money-safety` 가드 사각지대가 사라진다
- 전문 개정이 **YAML 1파일 수정**으로 끝나고, 설계서 문서가 자동 생성되어 드리프트가 0
- 반복부·버전을 스펙 층에서 표현할 수 있어 다건 이체·전문 개정 확장이 열린다

### 한계

- **전문 종수가 적으면 과투자다.** 현재 4종 규모에서 Phase 2 까지 가면 생성기 코드가 생성 대상보다
  커진다. Phase 2 착수는 전문이 **8종 이상**이거나 **반복부가 실제로 필요해진 시점**을 조건으로 한다
- Phase 3(카탈로그·시뮬레이터)은 개발 편의 도구라 **정합성·안전에 기여하지 않는다** — 보류
- 현행 레이아웃은 실제 은행 규격이 아니라 **관례 기반 자체 정의**다. 실 규격 확보 전까지 스펙 파일은
  "실제 전문의 모사"이며, 규격 입수 시 재작성이 필요하다
- 생성 소스는 JaCoCo 제외 대상이므로, **Codec 의 정확성은 생성물 커버리지가 아니라 계약 테스트**
  (픽스처 왕복 검증)로 보증해야 한다

### 배선 영향

| 항목 | 조치 |
|---|---|
| 헥사고날 | 생성물이 `adapter/out/.../fep/protocol/generated` — 프로토콜은 어댑터 관심사, 도메인 순수성 유지 |
| MSA 경계 | 서비스 추가 없음 → 스캔·JPA·gateway·nginx·Dockerfile 5곳 배선 불필요 |
| 커버리지 게이트 | `**/generated/**` JaCoCo 제외 추가 (`**/Q*.class` 선례) |
| OO 가드 | 생성물이 `record` + builder → setter 0, 가드 통과 |
| `harness-audit` | 최상위 디렉토리 2개 추가 → `docs/STRUCTURE.md` 갱신 필요 |

## 미결 질문

1. **Phase 2 착수 임계** — 전문 8종을 기준으로 잡았으나, 반복부 1건이 8종보다 강한 트리거일 수 있다
2. **실 전문 규격 확보** — 은행 설계서 없이 만든 스펙은 구조 검증만 되고 규격 검증은 안 된다
3. **EUC-KR 외 인코딩** — 일부 대외기관은 UTF-8·JIS 를 쓴다. `charset` 을 필드 단위로 둘지 전문 단위로 둘지
4. **스펙 파일의 소유권** — `telegram-spec/` 을 settlement-service 하위에 둘지 최상위에 둘지
   (다른 서비스가 대외 전문을 갖게 되면 최상위가 맞다)

## 착수 시 체크리스트

- [ ] Phase 1: YAML 스키마 확정 + 파서 + 스펙 검증 테스트(중복·총길이·dangling include)
- [ ] Phase 1: `FepLayouts` 4종을 YAML 로 이관, 기존 FEP 테스트 전부 GREEN 유지로 등가 증명
- [ ] Phase 2 전제: 전문 3종 추가(잔액조회·예금주조회·다건이체 반복부)로 생성기 정당화
- [ ] Phase 2: 생성 태스크 배선 + JaCoCo 제외 + 픽스처 왕복(encode→decode→동일값) 계약 테스트
- [ ] `docs/STRUCTURE.md`·`docs/ARCHITECTURE.md §7` 갱신, `harness-audit` 통과 확인

## 참조

- [0016 — Payout (출금) 펌뱅킹](0016-payout-domain-firm-banking.md) (본 ADR 이 확장하는 어댑터)
- [0024 — 이벤트 계약-as-code](0024-event-contract-as-code.md) (같은 사상의 Kafka 편 — 스펙 외부화 + 빌드 시점 강제)
- [0021 — shared-common 플랫폼 라이브러리화](0021-shared-common-as-platform-library.md) (composite build 선례)
- `docs/ARCHITECTURE.md §7` — MCI·EAI·ESB·FEP 용어 대응표
