import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { mapMarket, toBriefingEntry, buildUniverse } from '../../dart/universe.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const BIN = resolve(HERE, '..', '..', 'bin', 'build-briefing-universe.mjs');

// 실제 유효한 사업자등록번호(체크섬 통과) — 삼성전자 124-81-00998, NAVER 220-81-62517
const SAMSUNG_BIZ = '1248100998';
const NAVER_BIZ = '2208162517';

function companyBody({ corpCode, name, stockCode, corpCls, bizrNo, ceo, est }) {
  return {
    status: '000', corp_code: corpCode, corp_name: name, stock_code: stockCode,
    corp_cls: corpCls, bizr_no: bizrNo, ceo_nm: ceo, est_dt: est,
  };
}

test('mapMarket — corp_cls 코드를 시장 라벨로', () => {
  assert.equal(mapMarket('Y'), 'KOSPI');
  assert.equal(mapMarket('K'), 'KOSDAQ');
  assert.equal(mapMarket('N'), 'KONEX');
  assert.equal(mapMarket('E'), null);
  assert.equal(mapMarket(''), null);
  assert.equal(mapMarket(undefined), null);
});

test('toBriefingEntry — 유효 KOSPI 는 배치 항목으로 매핑(사업자번호 하이픈)', () => {
  const corp = { corpCode: '00126380', name: '삼성전자', stockCode: '005930' };
  const { entry, reason } = toBriefingEntry(
    corp, companyBody({ corpCode: '00126380', name: '삼성전자', stockCode: '005930', corpCls: 'Y', bizrNo: SAMSUNG_BIZ, ceo: '한종희', est: '19690113' }),
    { markets: ['KOSPI', 'KOSDAQ'] },
  );
  assert.equal(reason, null);
  assert.deepEqual(entry, {
    name: '삼성전자', stockCode: '005930', businessNumber: '124-81-00998',
    corpCode: '00126380', market: 'KOSPI', representativeName: '한종희', openingDate: '19690113',
  });
});

test('toBriefingEntry — 시장 필터 밖이면 제외', () => {
  const corp = { corpCode: 'c', name: '코닥사', stockCode: '111111' };
  const { entry, reason } = toBriefingEntry(
    corp, companyBody({ corpCls: 'N', bizrNo: NAVER_BIZ }), { markets: ['KOSPI', 'KOSDAQ'] },
  );
  assert.equal(entry, null);
  assert.match(reason, /대상 시장 아님\(KONEX\)/);
});

test('toBriefingEntry — 시장 미상/사업자번호 무효/종목코드 누락 제외', () => {
  assert.match(toBriefingEntry({ name: 'A', stockCode: '000111' }, companyBody({ corpCls: 'E' })).reason, /시장 구분 미상/);
  assert.match(
    toBriefingEntry({ name: 'A', stockCode: '000111' }, companyBody({ corpCls: 'Y', bizrNo: '1234567890' })).reason,
    /사업자등록번호 무효/,
  );
  assert.match(toBriefingEntry({ name: 'A', stockCode: 'ABC' }, companyBody({ corpCls: 'Y' })).reason, /종목코드 누락/);
});

test('toBriefingEntry — 대표자/개업일 없으면 선택 필드 생략(게이트 진위확인 생략용)', () => {
  const { entry } = toBriefingEntry(
    { name: '카카오', stockCode: '035720', corpCode: 'c' },
    companyBody({ corpCls: 'Y', bizrNo: '1208147521', est: 'invalid' }),
    { markets: ['KOSPI'] },
  );
  assert.equal(entry.representativeName, undefined);
  assert.equal(entry.openingDate, undefined);
});

test('buildUniverse — 보강·중복제거·조회실패 스킵·레이트리밋', async () => {
  const listed = [
    { corpCode: '1', name: '삼성전자', stockCode: '005930' },
    { corpCode: '2', name: 'NAVER', stockCode: '035420' },
    { corpCode: '3', name: '중복', stockCode: '005930' },   // stockCode 중복 → 스킵
    { corpCode: '4', name: '터짐', stockCode: '444444' },   // fetch throw → 스킵
    { corpCode: '5', name: '코넥스', stockCode: '555555' },  // KONEX → 시장 필터 스킵
  ];
  const bodies = {
    1: companyBody({ corpCls: 'Y', bizrNo: SAMSUNG_BIZ }),
    2: companyBody({ corpCls: 'K', bizrNo: NAVER_BIZ }),
    3: companyBody({ corpCls: 'Y', bizrNo: SAMSUNG_BIZ }),
    5: companyBody({ corpCls: 'N', bizrNo: NAVER_BIZ }),
  };
  const progress = [];
  let slept = 0;
  const result = await buildUniverse(
    {
      loadCorpCodes: async () => ({ companies: listed }),
      fetchCompany: async (code) => {
        if (code === '4') throw new Error('DART 013');
        return bodies[code];
      },
    },
    {
      markets: ['KOSPI', 'KOSDAQ'], delayMs: 10,
      onProgress: (m) => progress.push(m), sleepFn: async () => { slept += 1; },
    },
  );
  assert.equal(result.count, 2);
  assert.deepEqual(result.companies.map((c) => c.name), ['삼성전자', 'NAVER']);
  assert.deepEqual(result.companies.map((c) => c.market), ['KOSPI', 'KOSDAQ']);
  assert.equal(result.skipped.length, 3);
  assert.equal(progress.length, 5);
  assert.ok(slept >= 4); // 콜 간 지연 호출됨
});

test('buildUniverse — limit 은 상위 N 만', async () => {
  const listed = Array.from({ length: 5 }, (_, i) => ({ corpCode: String(i), name: `사${i}`, stockCode: `00000${i}` }));
  const result = await buildUniverse(
    { loadCorpCodes: async () => ({ companies: listed }), fetchCompany: async () => companyBody({ corpCls: 'Y', bizrNo: SAMSUNG_BIZ }) },
    { limit: 2 },
  );
  assert.equal(result.total, 2);
});

test('CLI — --help 는 사용법 출력 후 exit 0', () => {
  const run = spawnSync(process.execPath, [BIN, '--help'], { encoding: 'utf8' });
  assert.equal(run.status, 0);
  assert.match(run.stdout, /브리핑 유니버스 빌더/);
});

test('CLI — --dry-run 은 캐시만 읽어 키 없이 상장사 수 보고', () => {
  const workDir = mkdtempSync(join(tmpdir(), 'tca-univ-'));
  const cachePath = join(workDir, 'corp-codes.json');
  writeFileSync(cachePath, JSON.stringify({
    fetchedAt: new Date().toISOString(), totalCount: 2, listedCount: 2,
    companies: [
      { corpCode: '1', name: '삼성전자', stockCode: '005930', modifyDate: '20260101' },
      { corpCode: '2', name: 'NAVER', stockCode: '035420', modifyDate: '20260101' },
    ],
  }));
  try {
    const run = spawnSync(process.execPath, [BIN, '--dry-run'], {
      encoding: 'utf8',
      env: { ...process.env, CORP_CODES_CACHE: cachePath, DART_API_KEY: '' },
    });
    assert.equal(run.status, 0);
    assert.match(run.stdout, /\[dry-run\] 보강 없이 대상 상장사 2개사/);
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
});
