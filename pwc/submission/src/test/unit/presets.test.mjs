import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { runNode, withFetchStub } from './helpers/proc.mjs';
import { listPresets, loadPreset, resolveThresholds } from '../../common/presets.mjs';
import { DEFAULT_THRESHOLDS } from '../../common/signals.mjs';
import { EXTERNAL_THRESHOLDS } from '../../common/dart-signals.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DETECT_CLI = join(HERE, '..', '..', 'bin', 'detect-signals.mjs');
const CALIBRATE_CLI = join(HERE, '..', '..', 'bin', 'calibrate.mjs');
const PRELOAD = join(HERE, 'helpers', 'fetch-preload.mjs');

test('presets — 목록: 업종 3종 노출, 코호트 파일 제외, 미지 프리셋은 목록 담아 throw', () => {
  const names = listPresets();
  assert.deepEqual(names, ['commerce', 'manufacturing', 'semiconductor']);
  assert.throws(() => loadPreset('no-such'), /사용 가능: commerce, manufacturing, semiconductor/);
  // 모든 프리셋 값에는 근거(rationale)가 있어야 한다 — 임계값 방어 계약.
  for (const name of names) {
    const p = loadPreset(name);
    const keys = [...Object.keys(p.thresholds ?? {}), ...Object.keys(p.externalThresholds ?? {})];
    for (const key of new Set(keys)) {
      assert.ok(p.rationale?.[key], `${name}.${key} 에 rationale 없음`);
    }
  }
});

test('presets — resolveThresholds 병합: 기본 < 프리셋 < analysis-config < (플래그는 CLI 몫)', () => {
  // 기본만
  const base = resolveThresholds({ kind: 'external' });
  assert.equal(base.presetUsed, null);
  assert.deepEqual(base.thresholds, EXTERNAL_THRESHOLDS);

  // 프리셋 적용 (외부): commerce 는 재고·이자보상 키만 덮고 나머지는 기본 유지
  // (concentrationSharePct 는 내부(S2) 전용 키 — 외부 병합에서는 등장하지 않아야 한다)
  const ext = resolveThresholds({ preset: 'commerce', kind: 'external' });
  assert.equal(ext.thresholds.inventoryGapPp, 10);
  assert.equal(ext.thresholds.interestCoverageFloor, 12);
  assert.equal(ext.thresholds.arGrowthGapPp, EXTERNAL_THRESHOLDS.arGrowthGapPp);
  assert.equal('concentrationSharePct' in ext.thresholds, false);

  // 프리셋 적용 (내부)
  const int = resolveThresholds({ preset: 'commerce', kind: 'internal' });
  assert.equal(int.thresholds.concentrationSharePct, 50);
  assert.equal(int.thresholds.allocationGapPp, DEFAULT_THRESHOLDS.allocationGapPp);

  const dir = mkdtempSync(join(tmpdir(), 'tca-preset-'));
  try {
    // config 의 preset 지정 + config 값이 프리셋 값을 다시 덮음
    writeFileSync(join(dir, 'analysis-config.json'), JSON.stringify({
      preset: 'commerce',
      externalThresholds: { inventoryGapPp: 30 },
    }));
    const merged = resolveThresholds({ dataDir: dir, kind: 'external' });
    assert.equal(merged.presetUsed, 'commerce');
    assert.equal(merged.thresholds.inventoryGapPp, 30);         // config > preset(10)
    assert.equal(merged.thresholds.interestCoverageFloor, 12);  // preset 유지

    // 플래그(preset 인자)가 config 의 preset 보다 우선
    const flagged = resolveThresholds({ dataDir: dir, preset: 'semiconductor', kind: 'external' });
    assert.equal(flagged.presetUsed, 'semiconductor');
    assert.equal(flagged.thresholds.borrowingsGrowthPct, 60);
    assert.equal(flagged.thresholds.inventoryGapPp, 30); // config 는 여전히 최우선 (semiconductor 25 를 덮음)
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('detect-signals CLI — --preset commerce: presetUsed·임계값 반영 (--json)', () => {
  const r = runNode([DETECT_CLI, '--preset', 'commerce', '--json']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  const out = JSON.parse(r.stdout);
  assert.equal(out.presetUsed, 'commerce');
  assert.equal(out.thresholds.concentrationSharePct, 50);
  assert.equal(out.presentCount, 4); // 샘플 4신호는 민감 프리셋에서도 동일 판정
});

test('calibrate CLI — 스텁 코호트 발화율 표 (네트워크 0)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'tca-calib-'));
  try {
    const row = (sj, id, nm, [a0, a1, a2]) => ({
      sj_div: sj, account_id: id, account_nm: nm,
      bfefrmtrm_amount: String(a0), frmtrm_amount: String(a1), thstrm_amount: String(a2),
    });
    // 스트레스 재무 (E1~E4 발화) + 정정 3건·해명 1건 (E5 발화)
    const fullBody = {
      status: '000',
      list: [
        row('IS', 'ifrs-full_Revenue', '매출액', [800, 900, 1000]),
        row('IS', 'dart_OperatingIncomeLoss', '영업이익', [80, 90, 100]),
        row('BS', 'ifrs-full_CurrentTradeReceivables', '매출채권', [200, 220, 330]),
        row('BS', 'ifrs-full_Inventories', '재고자산', [100, 110, 160]),
        row('BS', 'ifrs-full_CurrentAssets', '유동자산', [300, 280, 260]),
        row('BS', 'ifrs-full_CurrentLiabilities', '유동부채', [200, 210, 230]),
        row('BS', '-표준계정코드 미사용-', '단기차입금', [50, 60, 90]),
        row('CF', 'ifrs-full_CashFlowsFromUsedInOperatingActivities', '영업활동현금흐름', [70, 60, 40]),
        row('CF', 'ifrs-full_InterestPaidClassifiedAsOperatingActivities', '이자의 지급', [5, 8, 15]),
      ],
    };
    const listRows = [
      { report_nm: '[기재정정]사업보고서', rcept_dt: '20260601' },
      { report_nm: '[기재정정]분기보고서', rcept_dt: '20260602' },
      { report_nm: '[첨부정정]주요사항보고서', rcept_dt: '20260603' },
      { report_nm: '풍문또는보도에대한해명(미확정)', rcept_dt: '20260604' },
    ];
    const stub = join(dir, 'stub.json');
    writeFileSync(stub, JSON.stringify({
      rules: [
        { match: 'fnlttSinglAcntAll.json', json: fullBody },
        { match: 'list.json', json: { status: '000', total_count: listRows.length, list: listRows } },
      ],
    }));
    const cohort = join(dir, 'cohort.json');
    writeFileSync(cohort, JSON.stringify({
      companies: [
        { name: '가상제조', corpCode: '00000001' },
        { name: '가상유통', corpCode: '00000002' },
      ],
    }));

    const r = runNode([CALIBRATE_CLI, '--cohort', cohort, '--json'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key' }));
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const out = JSON.parse(r.stdout);
    assert.equal(out.sampled, 2);
    const e1 = out.rates.find((x) => x.id === 'E1');
    assert.equal(e1.fired, 2);
    assert.equal(e1.ratePct, 100);
    assert.deepEqual(e1.companies, ['가상제조', '가상유통']);

    // 프리셋 적용이 발화율을 바꾸는지 — semiconductor 는 E2(재고 25%p)·E3(차입 60%) 둔감.
    const p = runNode([CALIBRATE_CLI, '--cohort', cohort, '--preset', 'semiconductor', '--json'],
      withFetchStub(PRELOAD, stub, { DART_API_KEY: 'test-key' }));
    const pOut = JSON.parse(p.stdout);
    assert.equal(pOut.presetUsed, 'semiconductor');
    assert.equal(pOut.rates.find((x) => x.id === 'E3').fired, 0); // 차입 +50% < 60% 임계
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
