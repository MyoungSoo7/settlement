# OpenAI Codex — 플러그인 & 스킬 공식 문서 정리

> 출처 (2026-07-07 기준):
> - 플러그인 개요: https://developers.openai.com/codex/plugins
> - 플러그인 만들기: https://developers.openai.com/codex/plugins/build
> - 스킬 작성법: https://developers.openai.com/codex/skills

---

## 1. 플러그인 개요 (Plugins)

### 1.1 플러그인이란

Codex 플러그인은 **스킬(Skills), 앱 통합(Apps), MCP 서버**를 하나의 재사용 가능한 워크플로우로 번들링한 배포 단위다.

| 구성 요소 | 역할 |
|-----------|------|
| **Skills** | 특정 작업을 위한 재사용 가능한 지침 |
| **Apps** | Gmail, Google Drive, Slack, GitHub 등 외부 도구 연결 |
| **MCP 서버** | 로컬 프로젝트 외부 시스템 접근 제공 |

### 1.2 설치 및 사용

- **앱**: Plugins 섹션에서 검색 → 설치 → (필요 시) 외부 앱 인증 → 새 스레드에서 사용
- **CLI**: `codex /plugins` 로 플러그인 목록 열기

사용 방법 2가지:
1. **자연어 설명**: "Gmail의 읽지 않은 메시지 요약해줘"처럼 원하는 결과를 그냥 설명
2. **명시적 호출**: `@` 기호로 특정 플러그인/스킬 직접 지정

### 1.3 권한·제거

- 설치된 플러그인은 기존 승인(approval) 설정을 따르고, 외부 서비스는 자체 인증·개인정보·데이터 정책 적용
- **제거**: 플러그인 브라우저에서 "Uninstall plugin"
- **비활성화**: `~/.codex/config.toml` 에서 `enabled = false` 후 재시작

### 1.4 추천 플러그인 예시

Codex Security(코드 스캔/취약점), Gmail, Google Drive, Slack, Sites(웹 배포)

---

## 2. 플러그인 만들기 (Build)

### 2.1 언제 플러그인을 만드나

- 개인 워크플로우만 필요 → **로컬 스킬**로 충분
- 팀/공개 배포가 필요 → **플러그인** (스킬 + MCP + 앱 통합 + 라이프사이클 훅을 함께 패키징)

### 2.2 디렉토리 구조

```
my-plugin/
├── .codex-plugin/
│   └── plugin.json      # 필수: manifest (이 파일만 .codex-plugin/ 안에)
├── skills/
│   └── my-skill/
│       └── SKILL.md     # 선택: 스킬
├── hooks/
│   └── hooks.json       # 선택: 라이프사이클 훅
├── .app.json            # 선택: 앱/커넥터 매핑
├── .mcp.json            # 선택: MCP 서버 설정
└── assets/              # 선택: icon.png, logo.png, screenshot-*.png
```

규칙:
- `plugin.json` 만 `.codex-plugin/` 안, 나머지는 전부 플러그인 루트
- manifest 안 경로는 모두 `./` 로 시작하는 상대경로

### 2.3 plugin.json (manifest)

최소 구성:

```json
{
  "name": "my-first-plugin",
  "version": "1.0.0",
  "description": "Reusable greeting workflow",
  "skills": "./skills/"
}
```

전체 필드:

| 그룹 | 필드 | 설명 |
|------|------|------|
| 식별 | `name` | 케밥-케이스 고정 식별자(네임스페이스) |
| | `version` | 시맨틱 버전 (예: 0.1.0) |
| | `description` | 짧은 설명 |
| 출판자 | `author` | `{ name, email, url }` |
| | `homepage` / `repository` / `license` / `keywords` | 메타데이터 |
| 컴포넌트 경로 | `skills` | 스킬 폴더 (`"./skills/"`) |
| | `mcpServers` | MCP 설정 파일 (`"./.mcp.json"`) |
| | `apps` | 앱 매핑 파일 (`"./.app.json"`) |
| | `hooks` | 훅 설정 — 단일 경로, 경로 배열, 또는 인라인 객체 |
| 설치 화면 | `interface` | 아래 참조 |

`interface` 하위 필드: `displayName`, `shortDescription`, `longDescription`, `developerName`, `category`(Productivity/Research/Integration 등), `capabilities`(["Read","Write"]), `websiteURL`, `privacyPolicyURL`, `termsOfServiceURL`, `defaultPrompt`(스타터 프롬프트 배열), `brandColor`(hex), `composerIcon`, `logo`, `screenshots`(배열)

### 2.4 스킬 포함

```
skills/
└── hello/
    └── SKILL.md
```

```markdown
---
name: hello
description: Greet the user with a friendly message.
---

Greet the user warmly and ask how you can help.
```

스킬 여러 개 = `skills/` 아래 폴더 하나당 `SKILL.md` 하나.

### 2.5 MCP 서버 번들링 (.mcp.json)

```json
{
  "docs": { "command": "docs-mcp", "args": ["--stdio"] }
}
```

`{ "mcp_servers": { ... } }` 로 감싼 형식도 허용. 설치 후 사용자는 `~/.codex/config.toml` 에서 플러그인 범위 정책 조정 가능:

```toml
[plugins."my-plugin".mcp_servers.docs]
enabled = true
default_tools_approval_mode = "prompt"
enabled_tools = ["search"]

[plugins."my-plugin".mcp_servers.docs.tools.search]
approval_mode = "approve"
```

### 2.6 라이프사이클 훅 (hooks.json)

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "python3 ${PLUGIN_ROOT}/hooks/session_start.py",
            "statusMessage": "Loading plugin context"
          }
        ]
      }
    ]
  }
}
```

- 환경변수: `PLUGIN_ROOT`(설치 루트), `PLUGIN_DATA`(쓰기 가능 데이터 디렉토리), 하위호환용 `CLAUDE_PLUGIN_ROOT` / `CLAUDE_PLUGIN_DATA`
- **신뢰 정책**: 플러그인 번들 훅은 비관리형(unmanaged) 훅 — 사용자가 정의를 검토·신뢰하기 전까지 Codex 가 실행하지 않음

### 2.7 마켓플레이스 (marketplace.json)

Codex 가 마켓플레이스를 읽는 순서:
1. 공식 Plugin Directory (OpenAI 큐레이션)
2. Repo: `$REPO_ROOT/.agents/plugins/marketplace.json`
3. 레거시 호환: `$REPO_ROOT/.claude-plugin/marketplace.json`
4. 개인: `~/.agents/plugins/marketplace.json`

예시 (repo 마켓플레이스):

```json
{
  "name": "local-repo-plugins",
  "interface": { "displayName": "Local Repo Plugins" },
  "plugins": [
    {
      "name": "my-plugin",
      "source": { "source": "local", "path": "./plugins/my-plugin" },
      "policy": { "installation": "AVAILABLE", "authentication": "ON_INSTALL" },
      "category": "Productivity"
    }
  ]
}
```

플러그인 항목 필드:
- `source.source`: `"local"` | `"url"`(Git 루트) | `"git-subdir"`(Git 서브디렉토리)
- `source.path` / `source.url` / `source.ref`(기본 main) / `source.sha`
- `policy.installation`: `"AVAILABLE"` | `"INSTALLED_BY_DEFAULT"` | `"NOT_AVAILABLE"`
- `policy.authentication`: `"ON_INSTALL"` | `"ON_FIRST_USE"`

CLI 관리:

```bash
codex plugin marketplace add owner/repo [--ref main]
codex plugin marketplace add https://github.com/example/plugins.git [--sparse .agents/plugins]
codex plugin marketplace add ./local-marketplace-root
codex plugin marketplace list
codex plugin marketplace upgrade [marketplace-name]
codex plugin marketplace remove marketplace-name
```

### 2.8 생성·설치·공유

- **자동 생성(권장)**: 내장 `@plugin-creator` 스킬 — manifest 생성, 테스트용 로컬 마켓플레이스 항목 생성, 기존 폴더 연결까지 자동
- **설치 위치**: `~/.codex/plugins/cache/$MARKETPLACE_NAME/$PLUGIN_NAME/$VERSION/` (로컬 플러그인 버전은 `local`), 온/오프 상태는 `~/.codex/config.toml`
- **워크스페이스 공유**: 앱 Plugins → Created by you → Share → 멤버/그룹 추가 또는 링크. 워크스페이스 내부에만 공유(공개 Directory 미등록). 관리자는 `requirements.toml` 의 `features.plugin_sharing = false` 로 차단 가능
- **공개 발행**: 공식 Plugin Directory 등록·셀프서비스 발행은 "곧 출시 예정" (아직 비활성)

---

## 3. 스킬 작성법 (Skills)

### 3.1 스킬이란

작업 특화 능력을 Codex 에 추가하는 패키징 형식. 지침 + 리소스 + (선택) 스크립트로 구성되어 Codex 가 워크플로우를 안정적으로 따르게 한다. **플러그인은 스킬의 배포 단위.**

### 3.2 스킬 디렉토리 구조

```
my-skill/
├── SKILL.md             # 필수
├── scripts/             # 선택: 결정론적 동작용 스크립트
├── references/          # 선택: 참고 자료
├── assets/              # 선택
└── agents/openai.yaml   # 선택: 표시/정책 메타데이터
```

SKILL.md frontmatter (필수 필드 2개):

```yaml
---
name: skill-name
description: 정확한 발동/미발동 조건 설명
---
(이후 본문 = Codex 가 따를 지침)
```

### 3.3 스킬 저장 위치 (스코프)

| 범위 | 경로 | 용도 |
|------|------|------|
| REPO | `.agents/skills` (현재 디렉토리) | 특정 폴더 관련 팀 스킬 |
| REPO | `../.agents/skills` | 중첩 폴더의 상위 조직 스킬 |
| REPO | `$REPO_ROOT/.agents/skills` | 저장소 전체 공유 스킬 |
| USER | `$HOME/.agents/skills` | 개인 스킬 |
| ADMIN | `/etc/codex/skills` | 시스템 관리 스킬 |
| SYSTEM | (번들) | OpenAI 기본 스킬 |

### 3.4 발동 방식

- **명시적**: CLI/IDE 에서 `/skills` 또는 `$` 로 스킬 언급
- **암시적**: Codex 가 `description` 과 현재 작업을 매칭해 자동 선택

### 3.5 선택 메타데이터 — agents/openai.yaml

```yaml
interface:
  display_name: "사용자 표시명"
  short_description: "간단 설명"
  icon_small: "./assets/small-logo.svg"
  icon_large: "./assets/large-logo.png"
  brand_color: "#3B82F6"
  default_prompt: "스킬 사용 기본 프롬프트"

policy:
  allow_implicit_invocation: false   # 명시 호출만 허용

dependencies:
  tools:
    - type: "mcp"
      value: "toolName"
```

### 3.6 모범 사례

- 스킬 하나 = 작업 하나 (단일 책임)
- 결정론적 동작이 필요한 부분에만 스크립트 사용
- 명확한 입력/출력을 가진 명령형 단계로 지침 작성
- `description` 이 발동 조건을 정확히 기술하는지 검증

### 3.7 생성 방법 3가지

1. **자동**: `$skill-creator` 커맨드
2. **수동**: `SKILL.md` 직접 작성
3. **기록 재생**: record-and-replay 기능으로 실제 워크플로우를 스킬로 변환

스킬 변경은 자동 감지되며, 반영이 안 보이면 Codex 재시작.

---

## 4. Claude Code 와의 비교 메모 (참고)

| 항목 | Codex | Claude Code |
|------|-------|-------------|
| manifest | `.codex-plugin/plugin.json` | `.claude-plugin/plugin.json` |
| 스킬 파일 | `SKILL.md` (name/description frontmatter) | `SKILL.md` (동일 구조) |
| 스킬 스코프 | `.agents/skills` (repo/user/admin) | `.claude/skills`, `~/.claude/skills` |
| 마켓플레이스 | `.agents/plugins/marketplace.json` (레거시로 `.claude-plugin/marketplace.json` 도 읽음) | `.claude-plugin/marketplace.json` |
| 훅 환경변수 | `PLUGIN_ROOT`/`PLUGIN_DATA` (+`CLAUDE_PLUGIN_ROOT` 하위호환) | `CLAUDE_PLUGIN_ROOT` |
| 명시 호출 | `@플러그인`, `$스킬`, `/skills` | `/스킬명` |

→ Codex 는 Claude Code 플러그인 포맷과 상당 부분 호환되도록 설계되어 있음(레거시 marketplace 경로·`CLAUDE_PLUGIN_*` 환경변수 지원).
