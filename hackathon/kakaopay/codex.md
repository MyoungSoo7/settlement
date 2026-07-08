# Codex 플러그인 · 스킬 공식 문서 정리

> 출처 (OpenAI Codex 공식 문서):
> - https://developers.openai.com/codex/plugins
> - https://developers.openai.com/codex/plugins/build
> - https://developers.openai.com/codex/skills

## 1. 플러그인 개요

플러그인은 **skills, 앱 연동(apps), MCP 서버를 하나의 재사용 가능한 워크플로우로 묶은 패키지**다
("Plugins bundle skills, app integrations, and MCP servers into reusable workflows for Codex").

**구성 요소 3가지**

| 요소 | 역할 |
|---|---|
| Skills | 특정 작업을 위한 재사용 가능한 지침 (reusable instructions) |
| Apps | GitHub, Slack, Google Drive 등 외부 도구 연결 |
| MCP servers | 프로젝트 외부 시스템에 대한 접근을 제공하는 서버 |

**설치·관리**

- CLI 에서 `codex` 실행 후 `/plugins` 로 플러그인 브라우저를 연다.
- 설치: 검색/브라우징 → 'Install plugin'(CLI) 또는 '+ Add to Codex'(앱) → 필요 시 외부 앱
  인증 → 새 스레드에서 사용.
- 비활성화는 설정 파일에서:

  ```toml
  [plugins."gmail@openai-curated"]
  enabled = false
  ```

- 제거: 플러그인 브라우저에서 **Uninstall plugin** — 단, 번들됐던 앱은 ChatGPT 쪽에서
  따로 관리해야 한다.
- 배포·공유: 프로젝트/팀의 **저장소 마켓플레이스(repo marketplace)** 를 통해 공유한다.
  공개(퍼블릭) 플러그인 배포는 향후 지원 예정.
- 권한: 설치 후에도 기존 승인 설정이 유지되며, 연결된 외부 서비스는 각자의 인증·개인정보
  정책을 따른다.

## 2. 플러그인 만들기

**빠른 시작**: `@plugin-creator` 스킬을 쓰면 필수 매니페스트(`.codex-plugin/plugin.json`)와
로컬 마켓플레이스 항목을 스캐폴드해 준다.

**디렉터리 구조** — 매니페스트만 `.codex-plugin/` 안에, 나머지 구성 요소는 플러그인 루트에 둔다:

```
my-plugin/
├── .codex-plugin/
│   └── plugin.json      # 필수 매니페스트
├── skills/
│   └── my-skill/
│       └── SKILL.md
├── hooks/
│   └── hooks.json
├── .mcp.json            # MCP 서버 설정
├── .app.json            # 앱 연동 설정
└── assets/              # 아이콘·스크린샷 등 시각 자산
```

**plugin.json 필드**

- 필수: `name`(kebab-case), `version`, `description`
- 선택: `author`, `homepage`, `repository`, `license`, `keywords`,
  `skills`/`mcpServers`/`apps`/`hooks`(컴포넌트 경로), `interface`(설치 화면 메타데이터 —
  `displayName`, `shortDescription`, `longDescription`, `developerName`, `category`,
  `capabilities`, `composerIcon`, `logo`, `screenshots`, `brandColor`)
- 경로 규칙: 매니페스트의 모든 경로는 `./` 로 시작하는 **플러그인 루트 기준 상대 경로**이고,
  루트 바깥을 가리킬 수 없다.

**컴포넌트 작성 형식**

- MCP 서버 (`.mcp.json`):

  ```json
  { "docs": { "command": "docs-mcp", "args": ["--stdio"] } }
  ```

  (`{ "mcp_servers": { ... } }` 로 감싼 형식도 허용)

- 라이프사이클 훅 (`hooks/hooks.json`):

  ```json
  {
    "hooks": {
      "SessionStart": [
        { "hooks": [ { "type": "command", "command": "python3 ${PLUGIN_ROOT}/hooks/session_start.py" } ] }
      ]
    }
  }
  ```

  훅 명령은 `PLUGIN_ROOT`, `PLUGIN_DATA` 환경변수를 받는다.
  **주의**: 플러그인 훅은 사용자가 검토·신뢰(trust)하기 전까지는 실행되지 않고 스킵된다.

**테스트·배포 (로컬 마켓플레이스)**

- 저장소 단위: `$REPO_ROOT/.agents/plugins/marketplace.json`
- 개인 단위: `~/.agents/plugins/marketplace.json`

  ```json
  {
    "name": "marketplace-name",
    "plugins": [
      {
        "name": "plugin-name",
        "source": { "source": "local", "path": "./plugins/my-plugin" },
        "policy": { "installation": "AVAILABLE", "authentication": "ON_INSTALL" }
      }
    ]
  }
  ```

- 마켓플레이스 CLI:

  ```bash
  codex plugin marketplace add owner/repo
  codex plugin marketplace list
  codex plugin marketplace remove marketplace-name
  ```

- 설치된 플러그인 캐시 위치: `~/.codex/plugins/cache/$MARKETPLACE_NAME/$PLUGIN_NAME/$VERSION/`
- 워크스페이스 공유: Codex 앱의 Plugins > Created by you > Share (워크스페이스 내부로 한정).

## 3. 스킬 작성법

스킬은 Codex 에 작업별 역량을 더하는 패키징 방식으로, **지침 + 리소스 + (선택) 스크립트**로
구성된다.

**SKILL.md 필수 형식** — frontmatter 는 `name` 과 `description` 두 필드:

```yaml
---
name: skill-name
description: Explain exactly when this skill should and should not trigger.
---

Skill instructions for Codex to follow.
```

`description` 이 트리거 판단 기준이므로 **언제 발동해야 하고 언제 발동하면 안 되는지**를
명확히 적는 것이 핵심이다.

**스킬 디렉터리 구조**

```
my-skill/
├── SKILL.md             # 필수
├── scripts/             # 선택 — 결정론적 동작이 필요할 때만
├── references/          # 선택 — 참고 자료
├── assets/              # 선택
└── agents/openai.yaml   # 선택 — 표시 메타데이터·정책·의존성
```

`agents/openai.yaml` 로 표시명/아이콘(`interface`), 암시적 호출 허용 여부
(`policy.allow_implicit_invocation`), MCP 도구 의존성(`dependencies.tools`)을 지정할 수 있다.

**저장 위치별 스코프**

| 위치 | 경로 | 용도 |
|---|---|---|
| REPO (CWD) | `.agents/skills` | 특정 폴더 전용 |
| REPO (상위) | `../.agents/skills` | 중첩 폴더 공유 |
| REPO (루트) | `$REPO_ROOT/.agents/skills` | 저장소 전체 공유 |
| USER | `$HOME/.agents/skills` | 개인 스킬 |
| ADMIN | `/etc/codex/skills` | 시스템 관리자 |
| SYSTEM | Codex 번들 | 기본 제공 |

**로딩·트리거 방식**

- **Progressive disclosure**: 이름·설명·경로만 먼저 컨텍스트에 올라가며, 이 인덱스는
  모델 컨텍스트의 최대 2% 또는 8,000자로 제한된다. 본문은 발동 시에만 로드.
- 호출 2가지: **명시적**(`/skills` 또는 `$스킬명` 멘션) / **암시적**(작업이 description 과
  일치하면 Codex 가 자동 선택).

**작성 모범 사례**

- 스킬 하나 = 목표 하나 (단일 책임)
- 결정론적 동작이 꼭 필요할 때만 스크립트를 쓰고, 그 외에는 지침으로
- 입력·출력을 명령형 단계로 명확히 표기
- description 을 다듬어 오발동/미발동을 방지

**관리**

- 생성: `$skill-creator` · 설치: `$skill-installer <이름>`
- 비활성화 (`~/.codex/config.toml`, 변경 후 Codex 재시작):

  ```toml
  [[skills.config]]
  path = "/path/to/skill/SKILL.md"
  enabled = false
  ```

---

### 참고 — 이 저장소와의 관련

이 저장소의 `settlement-copilot/`·`invest-copilot/` 은 위 공식 플러그인 규격이 나오기 전
패턴(루트 `AGENTS.md` 병합 + `~/.codex/prompts`/`~/.codex/skills` 복사 + `config.toml` MCP 등록,
`install-codex.sh`)으로 Codex 를 지원한다. 공식 규격 기준으로는 `.codex-plugin/plugin.json`
매니페스트 + 저장소 마켓플레이스(`$REPO_ROOT/.agents/plugins/marketplace.json`) 방식으로
마이그레이션할 수 있다 — 디렉터리 구조(skills/, hooks/hooks.json, .mcp.json)는 이미 거의 일치한다.
