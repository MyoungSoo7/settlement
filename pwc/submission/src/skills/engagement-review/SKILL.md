---
name: engagement-review
description: 엔게이지먼트 사이클의 재진단 델타 단계 서브 스킬 — 같은 고객을 재진단해 기준 대비 신호 델타(신규/해소/지속)를 계산하고 후속 리포트를 작성한다. 관리자(ceo-engagement-cycle)가 호출하는 내부 단계.
---

# Engagement Review — 재진단 델타 + 후속 리포트

전 사이클 브리핑 대비 "무엇이 나타났고, 무엇이 사라졌고, 무엇이 남았는가"를 결정론으로 잰다.

## 절차

1. **재진단**: 같은 조건으로 진단 패킷을 새로 만든다 (첫 진단과 동일 플래그 유지 —
   `--with-news`/`--with-market`/`--data-dir` 를 썼다면 그대로):
   ```text
   node <ROOT>/bin/diagnose-company.mjs --company <고객> [...동일 플래그] --json > <재진단packet.json>
   ```
2. **델타 계산 (결정론)**: 비교는 CLI 가 한다 — 손으로 대조하지 않는다:
   ```text
   node <ROOT>/bin/engagement-cycle.mjs delta --engagement <폴더> \
     --packet <재진단packet.json> --out <폴더>/delta-cycle-N.md
   ```
3. **후속 리포트 작성**: 델타 파일 + follow-up 이행 노트를 근거로 서술한다.
   - 신규 발화: 즉시 브리핑 후보 — 필요하면 관리자에게 새 브리핑 파이프라인 실행을 제안.
   - 해소: **이행 노트와 대조**해 "조치 후 해소(가설)"인지 "외생 요인"인지 구분 — 단정 금지.
   - 지속: 이행됐는데 지속이면 임계값/조치 설계 문제로 태깅 → retro 입력.
   - 델타 파일에 없는 수치는 쓰지 않는다 (지어내기 금지 — 채점기와 같은 원칙).
4. 완료 보고: 델타 요약(신규 n·해소 n·지속 n) + 델타 파일 경로 (retro 게이트의 --delta-file).

## 경계

phase 전이는 관리자가 `advance` 로 수행한다. 이 스킬은 델타 산출물과 서술만 만든다.
