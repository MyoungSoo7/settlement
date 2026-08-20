# C그룹 위성 서비스 합병 검토 (후속 작업, 미착수)

> 배경: [ADR 0037](../adr/0037-msa-decomposition-rationale.md) §3 에서 financial-statements/
> economics/market/common-data/company 5개를 "경계가 약한 C그룹 — 사실상 데이터 소스 단위 분리"로
> 분류하고 **합병 후보로 인지한 채 유지**하기로 결정했다. 이 문서는 그 결정을 실제로 실행할지
> 말지 판단하는 데 필요한 조사 항목과 옵션을 미리 정리해 둔 것 — **아직 조사도 결정도 안 됐다.**
> 지금 당장 실행할 필요는 없고, 시니어 서사 관점에서는 ADR 0037 로 이미 "근거를 알고 있다"는 점은
> 충분히 증명됐다. 이 문서는 다음에 실제로 손댈 때 처음부터 다시 고민하지 않기 위한 착수 준비물이다.

## 대상

| 서비스 | 역할 | ADR 0037 근거 |
|---|---|---|
| financial-statements-service | DART 재무제표 배치 수집 + 조회 | ⑥ 오너십 약함(외부 기관 소유 데이터) |
| economics-service | ECOS 경제지표 배치 수집 + 조회 | 상동 |
| market-service | KRX 시세/시총 수집 + 조회 | 상동 + market-stream(폴리글랏) 과 결합도 있음 |
| common-data-service | data.go.kr 범용 커넥터 | 상동, 사실상 데이터소스 등록형 프레임워크 |
| company-service | 뉴스·평판 수집 + LLM 감성분석 | 상동 + 문서함(docx) 기능은 별도 성격 |

## 조사 항목 체크리스트 (실행 전 채워야 할 것)

- [ ] 서비스별 실제 수집 트리거 방식·주기 실측(cron 표현식, `/admin/**` 수동 트리거 빈도) — 배치
      SLA 가 얼마나 다른지가 병합 시 장애 격리 손실 크기를 결정한다.
- [ ] 외부 API rate limit/쿼터 정책 확인(DART/ECOS/KRX/data.go.kr 각각) — 한 프로세스에 몰았을 때
      한쪽 쿼터 소진이 다른 데이터 수집을 막는지.
- [ ] `adapter/in/web`·`adapter/out/external` 계층의 코드 중복도 — "데이터소스 등록 → 수집 →
      정규화 → admin 게이트" 패턴이 얼마나 겹치는지(겹칠수록 common-data 의 범용 커넥터 프레임워크로
      나머지를 흡수하는 옵션 B 의 근거가 강해진다).
- [ ] 소비자(loan/investment 신용·투자 심사)가 이 5개를 호출하는 방식 — REST 동기 호출인지, 이벤트
      구독인지. 병합 시 API 계약이 바뀌면 소비자 쪽 변경 범위도 같이 잡아야 한다.
- [ ] DB 스키마 독립성 — 물리 서비스는 합쳐도 `lemuel_financial`/`lemuel_economics`/`lemuel_market`/
      `lemuel_commondata` 를 각자 유지할지(서비스만 합치고 DB-per-service 는 유지), 아니면 DB 도
      합칠지. 후자는 ADR 0020 의 "DB 물리 분리로 결합 차단" 원칙과 충돌하므로 기본값은 전자.
- [ ] gateway 라우팅·nginx·harness-guard 라우팅 맵 변경 범위 — `msa-service-wiring` 스킬의 5곳
      배선(스캔·JPA·gateway·nginx·Dockerfile) 을 서비스 5개→1~2개로 줄일 때 얼마나 건드리는지.
- [ ] CI/게이트 통합 비용 — 각 서비스의 JaCoCo/ArchUnit/admin-key 게이트를 하나로 합칠 때 테스트
      리소스 경합(Testcontainers 등)이 생기는지.
- [ ] **포트폴리오 환경 한계 명시**: 실제 프로덕션 트래픽/호출 빈도 데이터가 없다 — 이 판단은
      실측이 아니라 설계 추정에 근거한다는 점을 최종 결정 문서에도 남길 것.

## 병합 옵션 (예시 — 확정 아님)

| 옵션 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **A. 단일 external-data-service** | financial+economics+market+common-data 4개를 하나로, company 는 LLM 파이프라인 성격이 달라 별도 유지 | 서비스 수 4→1, 배선·CI 유지비용 대폭 감소 | 배치 주기/쿼터가 다른 4개를 한 프로세스에 몰아 장애 격리(③) 손실 |
| **B. common-data 프레임워크로 흡수** | "병합"이 아니라, common-data 의 데이터소스 등록형 커넥터 프레임워크를 확장해 financial/economics/market 이 그 위에 데이터소스로 등록되는 방향 | 이미 있는 SSRF 가드·admin 게이트·멱등 upsert 인프라 재사용, 서비스 코드 증분 최소 | market 은 시세 도메인 특유 로직(종목마스터 upsert 등)이 있어 순수 커넥터로 환원 안 될 수 있음 |
| **C. 현행 유지** | ADR 0037 §4 결정대로 유지 | 재작업 비용 0 | C그룹 유지비용(5개 CI/게이트) 계속 지불 |

## 판단 기준 (Go/No-Go)

ADR 0037 6축을 병합 후 상태에 재적용한다 — **병합으로 잃는 ③(장애 격리)·④(배포 주기 독립성)가
얻는 ⑤(팀 인지부하 감소)·CI 유지비용 절감보다 작을 때만 진행**한다. 지금은 이 비교를 뒷받침할
실측 데이터(위 체크리스트)가 없으므로 Go/No-Go 자체가 아직 미정이다.

## 실행 시 참고 (Go 로 결정된 경우에만)

- 컷오버 절차는 ADR 0020 의 Strangler 패턴(단계적 dual-run → 컷오버 → 백필)을 참고한다. 금융/공개
  데이터라 빅뱅 병합은 지양.
- 병합 결정이 내려지면 이 문서를 근거로 별도 ADR(0037 을 Superseded 처리하지 않고, 신규 번호로
  "C그룹 통합" ADR 을 추가하는 방식 — `docs/adr/README.md` 규칙 2)을 작성한다.

## 우선순위

**낮음.** 포트폴리오 서사 관점에서는 ADR 0037 로 "약한 경계를 알고 있다"는 점이 이미 증명됐고,
실제 코드 병합은 서사를 더 강화하지만 필수는 아니다. 착수는 다른 우선 작업이 없을 때 선택적으로.

## 참조

- [ADR 0037 — MSA 서비스 경계 근거](../adr/0037-msa-decomposition-rationale.md)
- [ADR 0020 — order↔settlement DB 물리 분리 (Strangler 패턴 참고)](../adr/0020-order-settlement-db-split.md)
- `msa-service-wiring` 스킬(서비스 배선 5곳)
