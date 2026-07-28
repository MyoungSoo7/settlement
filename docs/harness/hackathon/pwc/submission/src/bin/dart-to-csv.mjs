#!/usr/bin/env node
/**
 * DART 공시 재무제표를 CEO 리스크 분석용 public CSV 로 변환한다.
 *
 * 생성물은 내부 장부가 아니라 공시 기반 요약이다. 따라서 거래처별 aging,
 * 제품별 원가배분, 내부 원장 break 분석을 대체하지 않는다.
 *
 * 사용:
 *   node src/bin/dart-to-csv.mjs --corp-code 00164779 --stock-code 000660 \
 *     --company-name SK하이닉스 --years 2025,2026 --reports 11011,11013 \
 *     --out-dir src/data/generated/sk-hynix
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { financialSummary } from '../dart/client.mjs';

const ACCOUNT_ORD = {
  total_assets: '5',
  total_liabilities: '11',
  total_equity: '21',
  sales: '23',
  operating_income: '25',
  net_income: '29',
};
const HEADER = [
  'period',
  'bsns_year',
  'reprt_code',
  'corp_code',
  'stock_code',
  'company_name',
  'fs_div',
  'sales',
  'operating_income',
  'net_income',
  'total_assets',
  'total_liabilities',
  'total_equity',
  'currency',
  'source',
];
const REPORT_PERIOD = {
  11011: (year) => String(year),
  11012: (year) => `${year}H1`,
  11013: (year) => `${year}Q1`,
  11014: (year) => `${year}Q3`,
};

function usage() {
  console.error('usage: dart-to-csv.mjs --corp-code <8자리> --stock-code <6자리> --company-name <이름> --years <YYYY[,YYYY]> --reports <11011[,11013]> --out-dir <dir> [--fs-div CFS|OFS]');
}

function parseArgs(argv) {
  const out = { fsDiv: 'CFS' };
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!key.startsWith('--')) continue;
    i += 1;
    if (key === '--corp-code') out.corpCode = value;
    else if (key === '--stock-code') out.stockCode = value;
    else if (key === '--company-name') out.companyName = value;
    else if (key === '--years') out.years = String(value ?? '').split(',').map((v) => v.trim()).filter(Boolean);
    else if (key === '--reports') out.reports = String(value ?? '').split(',').map((v) => v.trim()).filter(Boolean);
    else if (key === '--out-dir') out.outDir = value;
    else if (key === '--fs-div') out.fsDiv = value;
  }
  return out;
}

function amount(value) {
  const n = Number(String(value ?? '').replace(/,/g, '').trim());
  return Number.isFinite(n) ? String(n) : '';
}

function csvCell(value) {
  const s = String(value ?? '');
  if (/[",\r\n]/.test(s)) return `"${s.replaceAll('"', '""')}"`;
  return s;
}

function findByOrd(list, ord, fsDiv) {
  return list.find((row) => row.fs_div === fsDiv && String(row.ord) === ord);
}

function rowFromSummary(summary, { corpCode, stockCode, companyName, year, reprtCode, fsDiv }) {
  const rows = summary?.list ?? [];
  const picked = Object.fromEntries(
    Object.entries(ACCOUNT_ORD).map(([key, ord]) => [key, findByOrd(rows, ord, fsDiv)]),
  );
  if (!Object.values(picked).some(Boolean)) return null;
  return {
    period: (REPORT_PERIOD[reprtCode] ?? ((y) => `${y}-${reprtCode}`))(year),
    bsns_year: year,
    reprt_code: reprtCode,
    corp_code: corpCode,
    stock_code: stockCode,
    company_name: companyName,
    fs_div: fsDiv,
    sales: amount(picked.sales?.thstrm_amount),
    operating_income: amount(picked.operating_income?.thstrm_amount),
    net_income: amount(picked.net_income?.thstrm_amount),
    total_assets: amount(picked.total_assets?.thstrm_amount),
    total_liabilities: amount(picked.total_liabilities?.thstrm_amount),
    total_equity: amount(picked.total_equity?.thstrm_amount),
    currency: Object.values(picked).find(Boolean)?.currency ?? 'KRW',
    source: 'DART',
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.corpCode || !args.stockCode || !args.companyName || !args.years?.length || !args.reports?.length || !args.outDir) {
    usage();
    process.exit(2);
  }

  const rows = [];
  for (const year of args.years) {
    for (const reprtCode of args.reports) {
      const summary = await financialSummary({ corpCode: args.corpCode, year, reprtCode });
      const row = rowFromSummary(summary, { ...args, year, reprtCode });
      if (row) rows.push(row);
    }
  }
  rows.sort((a, b) => a.period.localeCompare(b.period));

  mkdirSync(args.outDir, { recursive: true });
  const file = join(args.outDir, 'trial_balance_public.csv');
  const csv = [
    HEADER.join(','),
    ...rows.map((row) => HEADER.map((key) => csvCell(row[key])).join(',')),
  ].join('\n');
  writeFileSync(file, `${csv}\n`, 'utf8');
  console.log(JSON.stringify({ rows: rows.length, files: [file], note: 'DART 공시 기반 요약 CSV. 내부 aging/원가배분/원장 분석을 대체하지 않음.' }, null, 2));
}

main().catch((error) => {
  console.error('ERROR:', error.message);
  process.exit(1);
});
