---
name: company-news-rules
description: 기업 뉴스·평판·문서함 규칙 — 기사 본문 미저장(저작권)·url_hash 멱등, 평판 스코어 INSERT-only 스냅샷, 문서함 IDOR 차단(JWT ADMIN/MANAGER), 감성 fail-open. company-service 로직 작성·리뷰 시 로드.
---

# 기업 뉴스·평판 규칙 (company-service)

기업 뉴스 수집·평판 스코어·CEO 브리핑 문서함 위성 MSA(port 8090, lemuel_company, ADR 0023).
비즈니스 키 `stockCode`(6자리)로 financial/market 과 연계(코드·DB·이벤트 의존 0, 값만 공용).

## 기사 수집 (Article) — 저작권 불변식

- **★ 본문 전문 저장 금지**(저작권, ADR 0023). 필드는 8개뿐: urlHash·stockCode·source·title·summary(발췌)·publisher·url·publishedAt.
  body/content 필드 없음. `MAX_TITLE=500`, `MAX_SUMMARY=2000` 로 절단. summary 는 요약이지 전문 아님.
- **멱등 = 정규화 URL 의 SHA-256(urlHash)** + DB `url_hash UNIQUE`(최종방어). equals/hashCode 도 urlHash.
  URL 정규화는 `#fragment` 만 제거 — **트래킹 파라미터는 일부러 안 지운다**(오탐 시 서로 다른 기사 유실).
  다층 멱등: seenInBatch → existsByUrlHash 선체크 → DataIntegrityViolation catch. 재수집은 무해(saved=0), 예외로 안 죽음.
- `ArticleSource`: `NAVER_NEWS`(Phase 1 유일)/`DART_DISCLOSURE`/`RSS`. `NAVER_CLIENT_ID/SECRET` 미설정 → 수집 비활성. 쿼터 보호 200ms sleep.

## 감성·평판

- `Sentiment`: POSITIVE/NEGATIVE/NEUTRAL. `ArticleSentiment(sentiment, category)` 불변식: **category 는 NEGATIVE 에만** 부착
  (긍정/중립에 붙이면 예외). `IssueCategory` 가중치: FINANCIAL(3)·LEGAL(3)·GOVERNANCE(3)·LABOR(2)·PRODUCT(2), 미분류 부정=1, MAX_WEIGHT=3.
- `ReputationScore` v1: `score = 100 − round(100 × weightedPenalty / (total × MAX_WEIGHT))`, 0~100 clamp.
  긍정·중립 감점 없음, **부정 없으면/빈 목록이면 100점**. `ReputationGrade`: **A≥80 / B≥60 / C≥40 / D≥20 / E<20**.
- **★ INSERT-only 스냅샷** — 저장 후 UPDATE 금지. 산식이 바뀌어도 과거 스냅샷 불변(여신 감사 재현, 정산 commission_rate 와 동일 철학).
- 감성분석 provider(`app.company.sentiment.provider`): gemini/llm(claude)/키워드. **fail-open** — 키 미설정·LLM 실패 시
  키워드 폴백(평판 산정 안 막음). LLM 라벨은 정확히 하나, **알 수 없는 라벨은 NEUTRAL(보수적)**.

## 문서함 (CompanyDocument — CEO 브리핑) — IDOR 차단

- 메타만 도메인 소유(파일 바이트는 포트 인자). 업무키 `(stockCode, fileName)` — 같은 이름 재업로드=교체(`saveOrReplace`).
- 검증: stockCode `\d{6}`, 파일명 ≤255·**path traversal(`/`·`\`·`..`) 차단**, **Content-Type 은 클라이언트 불신 → 확장자
  허용목록(docx/pdf/png/md)에서 서버 파생**, 빈 파일 금지·`MAX_SIZE=20MB`. 등록된 stockCode 에만 업로드.
- **★ 다운로드·목록은 JWT ADMIN/MANAGER 게이트**(commit 01bf0f58f). 순차 Long id 라 무인증 열거=IDOR 였음.
  게이트웨이에 인증필터 없어 **서비스가 직접 게이팅** — 문서 규칙을 공개 GET 규칙보다 **먼저** 선언(첫 매칭 우선). 뉴스 메타 GET 은 공개 유지.
- 업로드는 `/admin/company/documents` — AdminApiKeyFilter + gateway 미라우팅.

## 보안·경계 (⚠ shared-common 의존 — CLAUDE.md "미의존" 은 낡음)

- **현재 shared-common 에 의존**(Phase 3 Outbox + JWT 검증 빈). 단 전역 SecurityConfig 는 배제하고 JWT 빈만 제한 `@Import`(격리 유지).
- `GET /api/company/**` permitAll(뉴스 메타 무인증), 문서 경로만 ADMIN/MANAGER, `/admin/company/**` 은 X-Internal-Api-Key(운영 fail-closed), denyAll.
- `app.jwt.secret ${JWT_SECRET}` — 발급 서비스와 동일 시크릿. corpCode(있으면 8자리)는 nullable.

## 안티패턴 (발견 시 지적)

- 기사 본문/원문 HTML 저장, summary 를 전문으로 채우기 / urlHash 없이 URL 원문 중복판정 / 트래킹 파라미터 공격적 제거.
- 평판 스냅샷 제자리 UPDATE(과거 등급 재현 불가) / NEGATIVE 아닌 기사에 category 부착.
- 문서 다운로드·목록 permitAll(IDOR 재발) / 문서 규칙을 공개 GET 뒤에 선언 / Content-Type 을 MultipartFile 헤더 그대로 신뢰.
- LLM 실패 시 폴백 대신 throw(평판 배치 중단) / 알 수 없는 LLM 라벨을 부정으로 매핑.
- CLAUDE.md "shared-common 미의존" 문구 그대로 신뢰(현재는 의존함).
