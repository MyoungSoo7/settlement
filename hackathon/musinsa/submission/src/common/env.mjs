import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const DEFAULT_MAX_PARENT_DEPTH = 8;

export function safeErrorMessage(error) {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string') {
    return error.message;
  }
  return 'Unknown error';
}

/** "${VAR}" 처럼 치환되지 않은 플레이스홀더가 그대로 넘어온 경우 — 키 부재로 취급한다.
 *  (Codex 플러그인 .mcp.json 의 env 블록은 ${VAR} 를 치환하지 않고 리터럴로 전달함을 실측 확인) */
function isUnexpandedPlaceholder(value) {
  return /^\$\{[^}]*\}$/.test(String(value ?? '').trim());
}

export function findEnvKey(name, startDir, maxParentDepth = DEFAULT_MAX_PARENT_DEPTH) {
  const value = process.env[name];
  if (value && !isUnexpandedPlaceholder(value)) return value;

  for (const origin of [startDir, process.cwd()]) {
    let dir = origin;
    for (let depth = 0; depth < maxParentDepth; depth += 1) {
      const envPath = join(dir, '.env');
      if (existsSync(envPath)) {
        const match = readFileSync(envPath, 'utf8').match(new RegExp(`^${name}=([^\\r\\n#]+)`, 'm'));
        if (match && !isUnexpandedPlaceholder(match[1])) return match[1].trim();
      }

      const parent = resolve(dir, '..');
      if (parent === dir) break;
      dir = parent;
    }
  }

  return '';
}
