#!/usr/bin/env node
/**
 * DART 수동 확인용 CLI (MCP 없이 빠르게 점검).
 *
 *   node src/bin/dart-cli.mjs search 삼성전자
 *   node src/bin/dart-cli.mjs company 00126380
 *   node src/bin/dart-cli.mjs disclosures 00126380 [days]
 *   node src/bin/dart-cli.mjs fin 00126380 2024 [11011] [CFS]
 */
import { company, disclosures, financialSummary } from '../dart/client.mjs';
import { searchCorp } from '../dart/corp-codes.mjs';

const [cmd, ...args] = process.argv.slice(2);
const ymd = d => d.toISOString().slice(0, 10).replaceAll('-', '');

const out = o => console.log(JSON.stringify(o, null, 2));

try {
  if (cmd === 'search') out(await searchCorp(args[0] ?? ''));
  else if (cmd === 'company') out(await company(args[0]));
  else if (cmd === 'disclosures') {
    const days = Number(args[1] ?? 30);
    out(await disclosures({
      corpCode: args[0],
      bgnDe: ymd(new Date(Date.now() - days * 86_400_000)),
      endDe: ymd(new Date()),
    }));
  } else if (cmd === 'fin') {
    out(await financialSummary({ corpCode: args[0], year: args[1], reprtCode: args[2] ?? '11011' }));
  } else {
    console.error('usage: dart-cli.mjs <search|company|disclosures|fin> ...');
    process.exit(2);
  }
} catch (e) {
  console.error('ERROR:', e.message);
  process.exit(1);
}
