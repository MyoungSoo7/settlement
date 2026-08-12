#!/usr/bin/env python3
"""RAG 지식베이스 동기화 — 리포의 문서를 ai-service 지식베이스에 맞춘다.

리포(`docs/knowledge/`)를 유일한 원본으로 두고, 실제 적재분(DB)을 여기에 수렴시킨다.
사람이 운영 콘솔에서 손으로 적재하지 않는다 — 손으로 넣은 문서는 리뷰를 거치지 않고,
무엇이 들어갔는지 리포만 봐서는 알 수 없기 때문이다.

적재 대상은 **매니페스트에 등재된 파일뿐이다** (deny-by-default).
`docs/knowledge/` 에 파일을 두는 것만으로는 적재되지 않고, `manifest.txt` 에 경로를
한 줄 추가해야 적재된다. 기본값이 "적재"라면 언젠가 누군가 내부 런북을 이 디렉터리에
떨어뜨리고, 그 내용은 이후 **모든 사용자 답변의 근거**로 프롬프트에 실린다. RAG 적재는
되돌리기 어려운 쓰기라 기본값이 "제외"여야 한다.

메타데이터를 매니페스트에 적지 않는 이유
--------------------------------------
제목은 문서의 H1 에서, 출처 URI 는 파일 경로에서 **파생**한다. 매니페스트에 제목을
따로 적으면 문서를 고칠 때 한쪽만 바뀌고, 그 상태로도 스크립트는 조용히 성공한다.
파생 값에는 그런 어긋남이 존재할 수 없다.

동작
----
    1. 매니페스트를 읽어 (경로 → 제목·출처 URI) 목록을 만든다
    2. 각 문서를 POST 한다 — 서버가 본문 해시를 비교해 안 바뀐 문서는 스스로 스킵한다
       (해시는 PII 마스킹 *이후* 값이라 로컬에서 미리 계산해 거를 수 없다. 서버가 판정한다)
    3. --prune: 매니페스트에 없는데 DB 에 있는 문서를 지운다 (고아 회수)
    4. --verify: 골든 질문을 검색해 기대한 문서가 상위에 잡히는지 본다

예시
----
    # 무엇이 바뀌는지만 본다 (네트워크 쓰기 없음)
    python3 scripts/knowledge/sync_knowledge.py --dry-run

    # CI 용 — 네트워크 없이 매니페스트와 파일의 정합성만 검사
    python3 scripts/knowledge/sync_knowledge.py --check-manifest

    # 실제 동기화 (고아 삭제 + 검색 검증까지)
    export KNOWLEDGE_ADMIN_TOKEN=...
    python3 scripts/knowledge/sync_knowledge.py --base-url http://localhost:8080 --prune --verify

의존성은 표준 라이브러리뿐이다 (리포의 다른 스크립트와 같은 규율).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

KNOWLEDGE_DIR = Path(__file__).resolve().parents[2] / "docs" / "knowledge"
MANIFEST = KNOWLEDGE_DIR / "manifest.txt"
GOLDEN_QUESTIONS = KNOWLEDGE_DIR / "golden-questions.txt"

# 적재 API 의 본문 상한과 같은 값. 여기서 먼저 걸러야 서버가 400 을 주기 전에
# "어느 파일이 문제인지"를 알 수 있다.
MAX_CONTENT_CHARS = 200_000


class SyncError(Exception):
    """복구 불가 — 호출자는 종료 코드 1 로 끝낸다."""


# ────────────────────────────── 매니페스트 ──────────────────────────────


class Document:
    """적재 단위 하나. 제목·출처 URI 는 모두 파생값이다."""

    def __init__(self, path: Path, content: str, title: str, source_uri: str):
        self.path = path
        self.content = content
        self.title = title
        self.source_uri = source_uri

    def __repr__(self) -> str:  # 로그용
        return f"{self.source_uri} ({self.path.name})"


def source_uri_for(relative: str) -> str:
    """파일 경로 → 출처 URI.

    `guide-funds.md` → `kb://guide-funds`. 규칙을 경로로 고정하는 이유: 출처 URI 는
    삭제 API 의 유일한 키이자 답변에 딸려 나가는 인용 표시다. 사람이 직접 적게 두면
    오타 하나로 같은 문서가 두 벌 적재되고, 둘 중 어느 쪽이 최신인지 알 수 없게 된다.
    """
    # 확장자 검사는 load_manifest 가 이미 마쳤다 (.md 아닌 등재는 거기서 실패한다).
    return "kb://" + relative[: -len(".md")]


def read_title(text: str, path: Path) -> str:
    """문서의 H1 을 제목으로 쓴다."""
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("# "):
            return stripped[2:].strip()
        if stripped:
            # 첫 비어 있지 않은 줄이 H1 이 아니면 형식 위반이다. 여기서 막지 않으면
            # 본문 중간의 아무 "# " 줄이 제목이 되어 답변 인용이 엉뚱해진다.
            break
    raise SyncError(f"{path}: 첫 줄이 '# 제목' (H1) 이 아닙니다 — 제목을 뽑을 수 없습니다")


def load_manifest() -> list[Document]:
    if not MANIFEST.exists():
        raise SyncError(f"매니페스트가 없습니다: {MANIFEST}")

    documents: list[Document] = []
    seen: dict[str, int] = {}

    for lineno, raw in enumerate(MANIFEST.read_text(encoding="utf-8").splitlines(), start=1):
        entry = raw.split("#", 1)[0].strip()
        if not entry:
            continue
        if entry in seen:
            raise SyncError(
                f"{MANIFEST}:{lineno}: 중복 등재 '{entry}' (최초 {seen[entry]}행). "
                "같은 문서를 두 번 적재하면 검색 결과가 자기 자신과 경쟁한다")
        seen[entry] = lineno

        path = KNOWLEDGE_DIR / entry
        if not path.is_file():
            raise SyncError(f"{MANIFEST}:{lineno}: 등재된 파일이 없습니다 — {path}")
        if path.suffix != ".md":
            raise SyncError(f"{MANIFEST}:{lineno}: 마크다운(.md) 만 적재합니다 — {entry}")

        text = path.read_text(encoding="utf-8")
        if not text.strip():
            raise SyncError(f"{path}: 본문이 비었습니다")
        if len(text) > MAX_CONTENT_CHARS:
            raise SyncError(
                f"{path}: 본문 {len(text):,}자 — 적재 상한 {MAX_CONTENT_CHARS:,}자를 넘습니다. "
                "문서를 쪼개세요 (상한은 임베딩 비용 상한입니다)")

        documents.append(Document(path, text, read_title(text, path), source_uri_for(entry)))

    if not documents:
        raise SyncError(f"{MANIFEST}: 등재된 문서가 없습니다")
    return documents


def check_manifest(documents: list[Document]) -> int:
    """네트워크 없이 리포 정합성만 검사한다 (CI 게이트).

    `load_manifest` 가 이미 "등재됐는데 파일이 없다"를 잡았으므로, 여기서는 반대 방향
    — **파일은 있는데 등재되지 않은 것** — 을 잡는다. 이쪽이 실제로 위험한 경우다:
    누군가 문서를 추가하고 매니페스트를 잊으면, 그 문서는 조용히 적재되지 않은 채
    "썼으니 챗봇이 안다"고 오해하게 된다.
    """
    listed = {doc.path.resolve() for doc in documents}
    unlisted = sorted(
        p for p in KNOWLEDGE_DIR.glob("*.md") if p.resolve() not in listed)

    for doc in documents:
        print(f"  OK   {doc.source_uri:<40} {len(doc.content):>7,}자  {doc.title}")

    if unlisted:
        print(f"\n매니페스트에 없는 문서 {len(unlisted)}개:", file=sys.stderr)
        for path in unlisted:
            print(f"  MISSING  {path.relative_to(KNOWLEDGE_DIR)}", file=sys.stderr)
        print("\n적재하려면 manifest.txt 에 추가하고, 적재하지 않을 문서라면 "
              "docs/knowledge/ 밖으로 옮기세요.", file=sys.stderr)
        return 1

    print(f"\n매니페스트 정합성 OK — 문서 {len(documents)}개")
    return 0


# ──────────────────────────────── HTTP ────────────────────────────────


def request(method: str, url: str, token: str | None = None,
            payload: dict | None = None) -> tuple[int, dict | None]:
    """HTTP 한 번. 4xx/5xx 도 예외로 만들지 않고 (status, body) 로 돌려준다 —
    204(없음)·409 같은 상태 코드 자체가 의미를 갖기 때문이다."""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(body)
        except json.JSONDecodeError:
            return exc.code, {"raw": body[:500]}
    except urllib.error.URLError as exc:
        raise SyncError(f"{method} {url} 연결 실패: {exc.reason}") from exc


def resolve_token(args: argparse.Namespace) -> str:
    token = args.token or os.environ.get("KNOWLEDGE_ADMIN_TOKEN")
    if token:
        return token
    if not (args.email and args.password):
        raise SyncError(
            "ADMIN 토큰이 없습니다. --token 또는 KNOWLEDGE_ADMIN_TOKEN 환경변수를 주거나 "
            "--email/--password 로 로그인하세요.")
    status, body = request("POST", f"{args.base_url}/auth/login",
                           payload={"email": args.email, "password": args.password})
    if status != 200 or not body or "token" not in body:
        raise SyncError(f"로그인 실패 ({status}): {body}")
    return body["token"]


# ──────────────────────────────── 동작 ────────────────────────────────


def fetch_remote(base_url: str, token: str) -> dict[str, dict]:
    status, body = request("GET", f"{base_url}/api/ai/knowledge/documents", token=token)
    if status == 401 or status == 403:
        raise SyncError(f"목록 조회 권한 없음 ({status}) — ADMIN 토큰이 맞는지 확인하세요")
    if status == 404:
        raise SyncError(
            "목록 API 가 없습니다 (404) — 배포된 ai-service 에 app.ai.rag.enabled=true 가 "
            "설정되지 않았거나, 목록 엔드포인트 이전 버전입니다")
    if status != 200 or body is None:
        raise SyncError(f"목록 조회 실패 ({status}): {body}")
    return {doc["sourceUri"]: doc for doc in body["documents"]}


def push(base_url: str, token: str, doc: Document) -> str:
    """문서 하나 적재. 반환값은 사람이 읽을 결과 라벨.

    본문이 그대로면 **서버가** 스킵한다(재임베딩 비용 0). 로컬에서 미리 판정하지 않는
    이유: 서버가 저장하는 해시는 PII 마스킹을 거친 본문의 해시라, 원본에 마스킹 대상이
    하나라도 있으면 로컬 해시와 값이 다르다. 로컬 판정은 "안 바뀌었는데 바뀌었다고
    보고"하거나 그 반대를 하게 된다.
    """
    status, body = request("POST", f"{base_url}/api/ai/knowledge/documents", token=token,
                           payload={"title": doc.title, "sourceUri": doc.source_uri,
                                    "content": doc.content})
    if status == 503:
        raise SyncError("임베딩이 설정되지 않았습니다 (503) — GEMINI_API_KEY 를 확인하세요")
    if status != 200 or body is None:
        raise SyncError(f"적재 실패 {doc.source_uri} ({status}): {body}")
    return "스킵(변경없음)" if body.get("skipped") else f"적재 {body.get('chunkCount')}청크"


def verify(base_url: str, token: str) -> int:
    """골든 질문 — 검색이 기대한 문서를 실제로 찾아내는지 본다.

    적재 성공(200)은 "벡터가 저장됐다"까지만 증명한다. 질문과 문서가 실제로 가까운지는
    검색해 봐야 안다. 임베딩 모델·차원·유사도 임계값 중 하나만 어긋나도 적재는 성공하고
    검색만 조용히 빈손이 된다 — 이 단계가 그 침묵을 깬다.
    """
    if not GOLDEN_QUESTIONS.exists():
        print(f"골든 질문 파일이 없습니다: {GOLDEN_QUESTIONS} — 검증을 건너뜁니다", file=sys.stderr)
        return 1

    failures = 0
    checked = 0
    for lineno, raw in enumerate(GOLDEN_QUESTIONS.read_text(encoding="utf-8").splitlines(), 1):
        entry = raw.split("#", 1)[0].strip()
        if not entry:
            continue
        if "\t" not in entry:
            raise SyncError(f"{GOLDEN_QUESTIONS}:{lineno}: '<질문>\\t<기대 출처 URI>' 형식이어야 합니다")
        question, expected = (part.strip() for part in entry.split("\t", 1))
        checked += 1

        query = urllib.parse.quote(question)
        status, body = request("GET", f"{base_url}/api/ai/knowledge/search?q={query}", token=token)
        if status != 200 or body is None:
            print(f"  FAIL  {question} — 검색 실패 ({status})", file=sys.stderr)
            failures += 1
            continue

        hits = body.get("hits", [])
        sources = [hit["sourceUri"] for hit in hits]
        if expected in sources:
            top = hits[sources.index(expected)]
            print(f"  OK    {question}  →  {expected} (유사도 {top['similarity']:.3f}, {sources.index(expected) + 1}위)")
        else:
            # 임계값(min-similarity) 미달이면 hits 자체가 빈다 — 두 실패를 구분해 보여준다.
            detail = "검색 결과 없음(임계값 미달)" if not hits else f"실제 상위: {', '.join(sources)}"
            print(f"  FAIL  {question}  →  {expected} 없음. {detail}", file=sys.stderr)
            failures += 1

    print(f"\n골든 질문 {checked}개 중 {checked - failures}개 통과")
    return 1 if failures else 0


# ──────────────────────────────── main ────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(
        description="docs/knowledge/ 매니페스트를 ai-service 지식베이스에 동기화한다",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("KNOWLEDGE_BASE_URL", "http://localhost:8080"),
                        help="게이트웨이 주소 (기본: http://localhost:8080)")
    parser.add_argument("--token", help="ADMIN JWT (환경변수 KNOWLEDGE_ADMIN_TOKEN 도 가능)")
    parser.add_argument("--email", help="--token 대신 로그인할 ADMIN 계정")
    parser.add_argument("--password", help="--email 과 함께 사용")
    parser.add_argument("--dry-run", action="store_true",
                        help="무엇이 적재/삭제될지만 출력하고 쓰지 않는다")
    parser.add_argument("--prune", action="store_true",
                        help="매니페스트에 없는데 적재돼 있는 문서를 삭제한다")
    parser.add_argument("--verify", action="store_true",
                        help="동기화 후 골든 질문으로 검색을 검증한다")
    parser.add_argument("--check-manifest", action="store_true",
                        help="네트워크 없이 매니페스트/파일 정합성만 검사한다 (CI 용)")
    args = parser.parse_args()

    args.base_url = args.base_url.rstrip("/")

    try:
        documents = load_manifest()

        if args.check_manifest:
            return check_manifest(documents)

        # --dry-run 도 목록 조회(읽기)는 한다. 무엇이 바뀌는지 말하려면 현재 상태를 알아야 한다.
        token = resolve_token(args)
        remote = fetch_remote(args.base_url, token)
        print(f"적재돼 있는 문서 {len(remote)}개 / 매니페스트 {len(documents)}개\n")

        for doc in documents:
            if args.dry_run:
                state = "신규" if doc.source_uri not in remote else "기존(변경 여부는 서버가 판정)"
                print(f"  [dry-run] POST {doc.source_uri:<40} {state}")
            else:
                print(f"  {push(args.base_url, token, doc):<16} {doc.source_uri}")

        orphans = sorted(set(remote) - {doc.source_uri for doc in documents})
        if orphans and not args.prune:
            # 경고로 끝낸다 — 삭제는 명시적 의사표시가 있을 때만 한다.
            print(f"\n매니페스트에 없는 적재분 {len(orphans)}개 (--prune 으로 삭제):", file=sys.stderr)
            for source_uri in orphans:
                print(f"  ORPHAN  {source_uri}", file=sys.stderr)
        elif orphans:
            print()
            for source_uri in orphans:
                if args.dry_run:
                    print(f"  [dry-run] DELETE {source_uri}")
                    continue
                status, _ = request("DELETE",
                                    f"{args.base_url}/api/ai/knowledge/documents"
                                    f"?sourceUri={urllib.parse.quote(source_uri)}", token=token)
                if status not in (204, 404):
                    raise SyncError(f"삭제 실패 {source_uri} ({status})")
                print(f"  삭제             {source_uri}")

        if args.verify:
            if args.dry_run:
                print("\n--dry-run 이므로 검증은 건너뜁니다 (적재하지 않은 상태의 검색은 무의미)")
                return 0
            print("\n골든 질문 검증")
            return verify(args.base_url, token)
        return 0

    except SyncError as exc:
        print(f"\n실패: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
