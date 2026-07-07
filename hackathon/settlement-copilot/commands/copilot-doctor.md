---
description: settlement-copilot 설치 상태 진단 — 설치본 드리프트·구버전 MCP 서버 감지 + 복구 안내
---

settlement-copilot 플러그인의 설치/동기화 상태를 진단하라.

1. `node settlement-copilot/scripts/doctor.mjs` 를 실행하라.
   (플러그인 저장소 밖이라면 이 커맨드에 병기된 절대 경로를 사용하라.)
2. doctor 출력의 **EXPECTED MCP TOOLS** 목록을, 지금 이 세션에 실제로 보이는
   settlement-copilot MCP 도구 목록과 직접 비교하라. 기대 목록에 있는데 세션에 없는 도구가
   있으면 **실행 중인 MCP 서버가 구버전**이다 — Claude Code 는 재시작(또는 `/mcp` 재연결),
   Codex CLI 는 세션 재시작을 안내하라.
3. doctor 의 항목별 판정(🟢/🟡/🔴)과 2번 비교 결과를 합쳐 요약 보고하라.
   🔴/🟡 항목에는 복구 명령을 병기하라 — 설치본 드리프트는
   `bash settlement-copilot/install-codex.sh` 재실행이 표준 복구다.

진단만 하고, 복구 명령은 사용자 확인 없이 실행하지 마라.
