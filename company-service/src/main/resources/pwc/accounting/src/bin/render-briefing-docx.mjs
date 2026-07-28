#!/usr/bin/env node
/**
 * briefing.md → briefing.docx 렌더 CLI (zero-dependency).
 *
 *   node src/bin/render-briefing-docx.mjs <briefing.md> [--out <briefing.docx>] [--title <제목>]
 *
 * 외부 documents 플러그인 없이도 CEO 제출용 Word 보고서를 만든다 —
 * UTF-8/한글 폰트가 코드로 보장되므로 "????" 인코딩 소실이 발생하지 않는다.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { briefingToDocx } from '../common/docx.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

const argv = process.argv.slice(2);
const flag = (name) => {
  const i = argv.indexOf(name);
  return i !== -1 && argv[i + 1] !== undefined ? argv[i + 1] : undefined;
};
const input = argv.find((a) => !a.startsWith('--') && a !== flag('--out') && a !== flag('--title') && a !== flag('--date') && a !== flag('--packet') && a !== flag('--snapshot-out'));

if (!input || argv.includes('--help') || argv.includes('-h')) {
  console.log('사용법: node src/bin/render-briefing-docx.mjs <briefing.md> [--out <briefing.docx>] [--title <제목>] [--date <YYYY-MM-DD>] [--packet <diagnostic-packet.json>] [--snapshot-out <executive-summary.png>]');
  process.exit(input ? 0 : 1);
}

const outPath = flag('--out') ?? input.replace(/\.md$/i, '') + '.docx';
const packetPath = flag('--packet');
const snapshotPath = flag('--snapshot-out') ?? (packetPath ? outPath.replace(/\.docx$/i, '') + '-executive-summary.png' : undefined);
if (packetPath && snapshotPath) {
  const result = spawnSync('python', [join(HERE, 'render-executive-snapshot.py'), packetPath, '--out', snapshotPath], {
    encoding: 'utf8',
    maxBuffer: 1024 * 1024,
  });
  if (result.status !== 0) {
    console.error(`스냅샷 PNG 생성 실패 — DOCX만 생성합니다.\n${result.stderr || result.stdout}`);
  }
}
const markdown = readFileSync(input, 'utf8');
const buffer = briefingToDocx(markdown, {
  title: flag('--title'),
  date: flag('--date') ?? new Date().toISOString().slice(0, 10),
  snapshotImagePath: snapshotPath,
});
writeFileSync(outPath, buffer);
console.log(`DOCX 생성: ${outPath} (${buffer.length.toLocaleString()} bytes)`);
if (snapshotPath) console.log(`스냅샷 PNG: ${snapshotPath}`);
