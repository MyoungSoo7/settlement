// Lemuel harness telemetry — append-only JSONL under .omc/logs (gitignored, never committed).
//
// Why this exists: guard.mjs blocks violations but kept no record of *what* it blocked, so
// "which guardrails actually fire" was unanswerable. This module gives every enforcement /
// routing layer a single non-fatal sink; telemetry-report.mjs aggregates it.
//
// Invariant: observability must never be able to break the guard or any hook — every write
// is best-effort and swallows all errors. Kill switch: HARNESS_TELEMETRY=off.

import { appendFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

export const LOG_DIR_SEGMENTS = ['.omc', 'logs'];

export function telemetryEnabled(env = process.env) {
  return env.HARNESS_TELEMETRY !== 'off';
}

export async function appendJsonl(repoRoot, fileName, records, { env = process.env } = {}) {
  if (!telemetryEnabled(env)) return false;
  const list = Array.isArray(records) ? records : [records];
  if (list.length === 0) return true;
  try {
    const directory = resolve(repoRoot, ...LOG_DIR_SEGMENTS);
    await mkdir(directory, { recursive: true });
    await appendFile(resolve(directory, fileName), list.map((record) => JSON.stringify(record)).join('\n') + '\n', 'utf8');
    return true;
  } catch {
    return false;
  }
}

export function guardHitRecords(mode, violations, now = new Date()) {
  const ts = now.toISOString();
  return violations.map((violation) => ({
    ts,
    mode,
    id: violation.id,
    file: violation.file,
    line: violation.line ?? null,
  }));
}

export async function logGuardHits(repoRoot, mode, violations, options = {}) {
  return appendJsonl(repoRoot, 'guard-hits.jsonl', guardHitRecords(mode, violations, options.now ?? new Date()), options);
}
