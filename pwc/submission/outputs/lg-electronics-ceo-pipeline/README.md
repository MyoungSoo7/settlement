# LG전자 CEO 리스크 브리핑 산출물

`ceo-consulting-pipeline.mjs`를 라이브 API(DART·ECOS·국세청·네이버 뉴스)로 실행해 만든 산출물입니다.

## 식별자

- 회사명: LG전자
- 사업자등록번호: 107-86-14075
- DART corp_code: 00401731
- 종목코드: 066570
- 뉴스 검색어: LG전자

## 실행 명령

```powershell
node src/bin/ceo-consulting-pipeline.mjs `
  --company "LG전자" `
  --business-number "107-86-14075" `
  --corp-code "00401731" `
  --stock-code "066570" `
  --with-news `
  --news-query "LG전자" `
  --news-display 20 `
  --out-dir "outputs/lg-electronics-ceo-pipeline"
```

외부 에이전트 CLI 호출이 실패해 `diagnostic-packet.json` 기준으로 `briefing.md`를 수동 생성한 뒤 자동 채점했습니다.

## 산출물

- `identity.json`: 사업자등록번호 체크섬 및 국세청 상태조회 결과
- `diagnostic-packet.json`: DART/ECOS/네이버 뉴스 진단 패킷
- `briefing.md`: CEO 브리핑 Markdown
- `executive-summary.png`: DART/뉴스/거시 신호를 한 장으로 요약한 CEO용 스냅샷
- `briefing.docx`: CEO 브리핑 Word 문서
- `eval.txt`: `briefing-eval --signals-file` 자동 채점 결과
- `prompt.txt`: 외부 에이전트용 프롬프트

## 검증

```powershell
node src/test/briefing-eval.mjs `
  --signals-file "outputs/lg-electronics-ceo-pipeline/diagnostic-packet.json" `
  "outputs/lg-electronics-ceo-pipeline/briefing.md"
```

결과: `EVAL PASS`

DOCX는 구조 검증했고, LibreOffice/Poppler 기반 PNG 렌더 QA도 수행했습니다. 결과는 `render-qa.txt`에 기록했습니다.

## 공개 식별 근거

- LG전자 공식 회사소개: https://www.lge.co.kr/company/main
- DART 회사정보: https://englishdart.fss.or.kr/dsbc001/selectPopup.ax?selectKey=00401731
