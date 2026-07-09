import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PIPELINE_CLI = join(HERE, '..', '..', 'bin', 'ceo-consulting-pipeline.mjs');
const FAKE_AGENT = join(HERE, 'helpers', 'fake-agent.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

// 체크섬 유효 사업자번호 (가중치 [1,3,7,1,3,7,1,3,5] 검산 통과값)
const VALID_BIZNO = '1234567891';

const row = (sj, id, nm, [a0, a1, a2]) => ({
  sj_div: sj, account_id: id, account_nm: nm,
  bfefrmtrm_amount: String(a0), frmtrm_amount: String(a1), thstrm_amount: String(a2),
});

// 건전 기업 3개년 공시 — 외부 신호 전부 absent → 음성 브리핑이 정답.
const HEALTHY_BODY = {
  status: '000',
  list: [
    row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000]),
    row('IS', 'dart_OperatingIncomeLoss', '영업이익', [80, 90, 100]),
    row('BS', 'ifrs-full_CurrentTradeReceivables', '매출채권', [160, 180, 200]),
    row('BS', 'ifrs-full_Inventories', '재고자산', [80, 90, 100]),
    row('BS', 'ifrs-full_CurrentAssets', '유동자산', [300, 320, 340]),
    row('BS', 'ifrs-full_CurrentLiabilities', '유동부채', [200, 200, 200]),
    row('BS', '-표준계정코드 미사용-', '단기차입금', [50, 50, 50]),
    row('CF', 'ifrs-full_CashFlowsFromUsedInOperatingActivities', '영업활동현금흐름', [120, 135, 150]),
    row('CF', 'ifrs-full_InterestPaidClassifiedAsOperatingActivities', '이자의 지급', [2, 2, 2]),
  ],
};

function stubRules() {
  return {
    rules: [
      // 국세청 상태조회 — 계속사업자(01)
      { match: 'nts-businessman/v1/status', json: { status_code: 'OK', data: [{ b_no: VALID_BIZNO, b_stt_cd: '01', b_stt: '계속사업자' }] } },
      { match: 'fnlttSinglAcntAll.json', json: HEALTHY_BODY }, // SinglAcnt 보다 먼저 (substring 겹침)
      { match: 'fnlttSinglAcnt.json', json: { status: '000', list: [
        { fs_div: 'OFS', sj_div: 'IS', account_nm: '매출액', thstrm_amount: '1,000' },
        { fs_div: 'OFS', sj_div: 'IS', account_nm: '영업이익', thstrm_amount: '100' },
      ] } },
      { match: 'company.json', json: { status: '000', corp_name: '테스트전자(주)', stock_code: '123456', ceo_nm: '홍길동', bizr_no: VALID_BIZNO } },
      { match: 'list.json', json: { status: '000', total_count: 1, list: [{ report_nm: '분기보고서', rcept_dt: '20260515' }] } },
      // ECOS 는 규칙 없음 → 599 → 거시 컨텍스트 생략 경로
    ],
  };
}

const NEGATIVE_BRIEFING = `# 점검 결과 — 테스트전자(주)
## 확인 범위
결론: 공시 기반 5개 축 점검에서 유의미한 이상 신호가 확인되지 않았습니다.
근거: 매출·채권·차입·유동성·공시 행간 지표 모두 기준 범위 내.
확신도: 확인됨 — 결정론 신호 파생 결과 PRESENT 0건.
권고 조치: 분기별 정기 점검 유지.
`;

test('pipeline — --help: 사용법 출력 (exit 0)', () => {
  const r = runNode([PIPELINE_CLI, '--help']);
  assert.equal(r.status, 0);
  assert.match(r.stdout, /identity gate -> diagnose-company -> agent briefing -> briefing-eval/);
});

test('pipeline — 필수 인자 누락: 사용법과 함께 중단 (exit 1)', () => {
  const r = runNode([PIPELINE_CLI, '--company', '테스트전자']);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /--business-number/);
});

test('pipeline — 사업자번호 체크섬 불량: 식별 게이트에서 분석 진입 차단 (exit 1)', () => {
  const out = mkdtempSync(join(tmpdir(), 'tca-pipe-gate-'));
  try {
    const r = runNode([PIPELINE_CLI, '--company', '테스트전자', '--business-number', '1234567890', '--out-dir', out]);
    assert.equal(r.status, 1, r.stdout + r.stderr);
    assert.match(r.stderr, /식별 게이트 실패/);
    const identity = JSON.parse(readFileSync(join(out, 'identity.json'), 'utf8'));
    assert.equal(identity.analysisAllowed, false);
    assert.ok(!existsSync(join(out, 'diagnostic-packet.json'))); // 게이트 FAIL 이면 진단 진입 금지
    assert.ok(existsSync(join(out, 'pipeline-next-steps.md')));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test('pipeline — --agent none: 게이트→진단까지 실행, 프롬프트·다음 단계 안내 (exit 0)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-pipe-none-'));
  try {
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify(stubRules()));
    const r = runNode(
      [PIPELINE_CLI, '--company', '테스트전자', '--business-number', VALID_BIZNO,
        '--corp-code', '00000001', '--year', '2025', '--agent', 'none', '--out-dir', join(dir, 'out')],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key', DATA_GO_KR_API_KEY: 'test-key' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /\[1\] 기업 식별 게이트/);
    assert.match(r.stdout, /\[2\] 진단 패킷 생성/);
    assert.match(r.stdout, /수동 절차로 전환/);
    const out = join(dir, 'out');
    assert.ok(existsSync(join(out, 'identity.json')));
    assert.ok(existsSync(join(out, 'diagnostic-packet.json')));
    assert.ok(existsSync(join(out, 'prompt.txt')));
    const nextSteps = readFileSync(join(out, 'pipeline-next-steps.md'), 'utf8');
    assert.match(nextSteps, /spreadsheets/);
    assert.match(nextSteps, /documents/);
    const prompt = readFileSync(join(out, 'prompt.txt'), 'utf8');
    assert.match(prompt, /PRESENT 신호만 리스크로 다룬다/);
    assert.match(prompt, /테스트전자/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('pipeline — 가짜 에이전트 전 구간: 게이트→진단→브리핑→채점 EVAL PASS (exit 0)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-pipe-full-'));
  try {
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify(stubRules()));
    const fixture = join(dir, 'fixture.md');
    writeFileSync(fixture, NEGATIVE_BRIEFING);
    const out = join(dir, 'out');
    const r = runNode(
      [PIPELINE_CLI, '--company', '테스트전자', '--business-number', VALID_BIZNO,
        '--corp-code', '00000001', '--year', '2025', '--agent', `node ${FAKE_AGENT}`, '--out-dir', out],
      withFetchStub(PRELOAD, stub, {
        DART_API_KEY: 'test-key', DATA_GO_KR_API_KEY: 'test-key',
        FAKE_BRIEFING_FILE: fixture, BRIEFING_JUDGE_PROVIDER: 'off',
      }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.match(r.stdout, /\[3\] 에이전트 브리핑 생성/);
    assert.match(r.stdout, /\[4\] 자동 채점/);
    assert.match(r.stdout, /EVAL PASS/);
    assert.match(r.stdout, /PIPELINE READY/);
    assert.ok(existsSync(join(out, 'briefing.md')));
    assert.match(r.stdout, /\[5\] Word 보고서 렌더/);
    assert.ok(existsSync(join(out, 'briefing.docx'))); // 내장 렌더러 자동 산출
    // 채점까지 끝난 뒤 next-steps 는 compliance/documents 단계로 안내한다.
    const nextSteps = readFileSync(join(out, 'pipeline-next-steps.md'), 'utf8');
    assert.match(nextSteps, /이미 생성·채점됨/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
