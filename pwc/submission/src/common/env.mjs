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

export function findEnvKey(name, startDir, maxParentDepth = DEFAULT_MAX_PARENT_DEPTH) {
  const value = process.env[name];
  if (value) return value;

  let dir = startDir;
  for (let depth = 0; depth < maxParentDepth; depth += 1) {
    const envPath = join(dir, '.env');
    if (existsSync(envPath)) {
      const match = readFileSync(envPath, 'utf8').match(new RegExp(`^${name}=([^\\r\\n#]+)`, 'm'));
      if (match) return match[1].trim();
    }

    const parent = resolve(dir, '..');
    if (parent === dir) break;
    dir = parent;
  }

  return '';
}
