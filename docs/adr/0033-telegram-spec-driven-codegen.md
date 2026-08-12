# ADR 0033 — 전문(電文) 스펙 주도 코드 생성 (telegram spec-driven codegen)

- 상태: **Accepted — Phase 1·2·3 구현 완료(2026-08-12). 시뮬레이터/개발자 포털만 의도적 제외**
- 일자: 2026-08-12
- 관련: ADR 0016(payout 펌뱅킹 — 본 ADR 이 **확장**하는 대상), ADR 0024(이벤트 계약-as-code — 같은 사상의
  Kafka 편), ADR 0021(shared-common composite build — 도구/스펙을 런타임 밖에 두는 선례),
  `docs/ARCHITECTURE.md §7`(MCI·EAI·ESB·FEP 대응표), `money-safety` 스킬
- 배경: 금융권 MDD(Model Driven Development) 툴이 "전문 모델 정의 → 소스 생성"으로 하는 일을,
  현행 FEP 어댑터는 **런타임 인터프리터로 절반만** 하고 있다. 나머지 절반(스펙 외부화 + 타입 안전)을
  어디에 둘 것인가에 대한 결정.

## Phase 1 구현 노트 (2026-08-12)

- 스펙 위치는 `settlement-service/src/main/resources/telegram/firmbanking/*.yaml` — Phase 1 은 런타임
  로더라 classpath 리소스가 자연스럽다. 최상위 `telegram-spec/` 이전은 Phase 2(코드 생성) 때 재검토하며,
  아래 **미결 질문 4 는 Phase 1 한정으로 해소**된 상태다.
- Phase 1 스펙 키는 **최소 집합**으로 확정했다 — 전문 `telegram·msgType·description·version·
  effectiveFrom·include·totalLength·fields`, 프래그먼트 `fragment·description·fields`, 필드 `name·length·type`.
  `required·scale·min·idempotencyKey` 는 강제 수단이 생기는 Phase 2(VO 생성)로 미룬다. **강제되지 않는
  선언을 스펙에 두면 "지켜지고 있다"는 착시**를 만들기 때문이다.
- `version`·`effectiveFrom` 은 파싱·보관만 하고 라우팅에는 쓰지 않는다(개정 병존은 Phase 3).
- 구현하며 확인된 사실 2건:
  1. 이체요청 총길이는 **113바이트**(공통부 34 + 개별부 79)다. 본 ADR 초안의 `totalLength: 68` 은 오기였고
     스펙 파일·본문 모두 바로잡았다. 선언 총길이 어서트가 실제로 이런 오기를 잡는다는 증거이기도 하다.
  2. `msgType: 0200` 을 인용부호 없이 쓰면 YAML 이 정수 200 으로 읽어 **선행 0 이 사라진다**. 스펙 로더가
     문자열이 아닌 msgType 을 거부하도록 규칙을 추가했다 — 설계 단계에서 예상하지 못한 함정이다.

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
totalLength: 113                  # 공통부 34 + 개별부 79 — 계산값과 불일치하면 로딩 실패
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

## Phase 2 구현 노트 (2026-08-12) — 설계와 달라진 점 포함

**전문 4종 → 10종.** Phase 2 착수 조건(8종 이상 또는 반복부 실수요)을 실제로 충족시키고 들어갔다 —
잔액조회(0100/0110)·예금주조회(0300/0310)·**다건이체(0220/0230, 반복부 포함)** 를 추가했다.

**⚠ 배치가 §2 설계와 다르다.** ADR 초안은 생성기를 composite build(`telegram-codegen`)에 두고 생성물을
`build/generated/telegram` 에 두는(git 미추적) QueryDSL 방식을 그렸다. 실제로는 **생성기를 test 소스셋에,
생성물을 `src/main/.../fep/protocol/generated/` 에 커밋**하는 방식을 택했다. 이유:

- 스펙 파서가 settlement-service main 에 있다. 생성기를 main 에 두면 *생성물을 컴파일하려면 생성기가,
  생성기를 컴파일하려면 main 이* 필요한 **순환**이 생긴다. test 소스셋은 main 을 자유롭게 쓰므로 순환이 없다.
- 순환을 끊는 정공법은 파서·코덱(`FepField`·`TelegramLayout`·`spec/*`)을 새 composite build 로 **추출**하는
  것인데, 그러면 ① Spring BOM 버전을 동기화해야 할 지점이 하나 더 생기고(shared-common 빌드 파일에 이미
  ⚠ 경고가 붙어 있다) ② 프로토콜 클래스가 어댑터 밖으로 나간다. 전문 10종 규모에서 그 값보다 비용이 크다.
- 대신 **드리프트 게이트**로 같은 보증을 만든다: `TelegramGeneratedSourcesTest` 가 매 빌드 스펙에서
  다시 생성해 커밋된 파일과 대조한다. 생성물을 손으로 고치거나 스펙만 고치고 재생성을 잊으면 빌드가 깨진다.
  재생성은 `./gradlew :settlement-service:generateTelegramSources`.

전문이 더 늘거나 다른 서비스가 대외 전문을 갖게 되면 composite build 추출을 재검토한다(미결 질문 4).

**`scale` 키를 도입했다.** Phase 1 은 의미 키를 전부 미뤘지만, 코드 생성 단계에서는 금액을 `BigDecimal` 로
승격할 근거가 필요하다. `scale` 이 선언된 N 필드만 `BigDecimal` 이 되고, 나머지 N(일련번호·일시·건수)은
선행 0 보존을 위해 `String` 으로 남는다 — `required`·`min` 같은 나머지 의미 키는 여전히 미도입이다.

**반복부(OCCURS)** 는 고정 횟수만 지원한다. 스펙에서는 구조로 보존하고(`TelegramElement.Repeated`),
코덱에는 `DETAIL_3_REF_ID` 형태로 펼쳐 넣는다 — 런타임 엔진(`TelegramLayout`)은 손대지 않았다.
디코딩은 **선언 건수를 그대로** 돌려준다(빈 슬롯 포함). 값이 비었다는 이유로 슬롯을 버리면 은행이 보낸
실패 건을 놓치기 때문이고, 유효 건수는 전문의 건수 필드가 알려준다.

**생성물**: 전문 10종 × (VO record + Codec) = 20파일. 반복부는 `List<Detail>`, 금액은 `BigDecimal`,
최대 건수 초과·음수 금액·규격 초과 정밀도는 인코딩이 거부한다.

## Phase 3 구현 노트 (2026-08-12)

Phase 3 를 셋으로 갈라, **기능 공백 3건은 구현하고 개발 편의 도구는 제외**했다.

**① 가변 반복부.** 다건이체(0220/0230)를 고정 5건에서 **건수 필드 기반 가변**(`countField`+`max: 100`)으로
바꿨다. 고정 반복은 1건을 보낼 때도 빈 슬롯을 채워 보내야 하고 상한을 늘리는 순간 전문이 통째로 커진다.
설계상 강제한 것 셋:

- 가변부는 **전문 마지막**에만 올 수 있다 — 뒤에 필드가 있으면 그 offset 이 건수에 따라 밀린다.
- 건수 필드는 반복부보다 **앞**에 있고 `N` 이어야 한다 — 디코딩은 레이아웃을 만들기 전에 건수를 읽어야 한다
  (`TelegramSpec.readOccurrences`).
- 가변 전문에는 `totalLength` 를 선언할 수 없다(건수마다 달라진다). 상한은 `max` 로 표현한다.
- 인코딩은 건수 필드와 실제 명세 건수의 **불일치를 거부**한다. 어긋난 전문은 은행에서 반송되거나,
  더 나쁘게는 앞 n건만 처리되고 나머지가 조용히 누락된다.

**② 개정 병존.** 잔액응답 0110 에 개정 2(최종거래일자 추가, 2026-07-01 시행)를 넣어 실제로 공존시켰다.
유일성 기준을 전문구분코드에서 **`(전문명, 개정번호)`** 로 옮기고, 수신 해석은 `byMsgType(code, asOf)` 가
기준일에 시행 중인 최신 개정을 고른다. 개정이 둘 이상인 코드를 날짜 없이 조회하면 **실패한다** —
어느 규격으로 해석할지 모르는 채 넘어가는 것이 가장 위험하다. 생성 클래스는 `BalanceResponseV2Telegram`
처럼 개정 접미사를 붙여 구 개정과 공존한다.

**③ 전문 설계서 자동 산출.** ADR §3 이 생성물로 약속했으나 Phase 2 에서 빠졌던 것 — `docs/telegram/` 에
전문별 규격서(offset·길이·타입·금액 표시)와 목차를 생성한다. 드리프트 게이트가 코드와 함께 대조한다.

**제외: 시뮬레이터/개발자 포털.** 정합성·안전에 기여하지 않는 개발 편의 도구라는 초안의 판단을 유지한다.
`MockBankServer` 는 이미 카탈로그를 거쳐 동작하므로 별도 전환이 필요 없다.

**구현 중 발견한 함정 — `Map.copyOf` 는 순서를 보존하지 않는다.** 카탈로그 색인을 `Map.copyOf`/`Set.copyOf`
로 만들었더니 순회 순서가 **JVM 실행마다 달라졌다**(불변 컬렉션은 실행마다 randomize 되는 salt 를 쓴다).
그 순서가 곧 생성 순서라 같은 스펙에서 매번 다른 설계서가 나왔고, 드리프트 게이트가 무작위로 깨졌다.
`LinkedHashMap` 으로 선언 순서를 보존하고, 목차는 `(코드, 개정)` 으로 다시 정렬해 이중으로 고정했다.
**코드 생성기의 출력은 결정적이어야 한다** — 이 저장소에서 다시 밟기 쉬운 함정이라 여기 남긴다.

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
- Phase 3 중 시뮬레이터·개발자 포털은 개발 편의 도구라 **정합성·안전에 기여하지 않아** 제외한다
  (가변부·개정 병존·설계서 산출은 기능 공백이므로 구현했다 — 위 Phase 3 노트)
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
