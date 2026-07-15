/**
 * 자식 프로세스용 fetch 스텁 프리로드 — NODE_OPTIONS="--import=<이 파일 URL>" 로 주입.
 * FETCH_STUB_FILE(JSON) 의 rules 를 순서대로 검사해 URL 에 match 문자열이 포함되면
 * 해당 canned 응답을 돌려준다. 매칭이 없으면 599 (테스트가 놓친 네트워크 호출 검출).
 */
import { readFileSync } from 'node:fs';

const specFile = process.env.FETCH_STUB_FILE;
if (specFile) {
  const spec = JSON.parse(readFileSync(specFile, 'utf8'));
  globalThis.fetch = async (url) => {
    const target = String(url);
    const rule = spec.rules.find((r) => target.includes(r.match));
    if (!rule) return new Response(`no stub for ${target}`, { status: 599 });
    const body = rule.base64 != null ? Buffer.from(rule.base64, 'base64') : JSON.stringify(rule.json);
    return new Response(body, { status: rule.status ?? 200 });
  };
}
