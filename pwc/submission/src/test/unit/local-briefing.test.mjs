import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluateBriefing, signalsFromPacket } from '../briefing-eval.mjs';
import { renderLocalBriefing } from '../../common/local-briefing.mjs';

test('local briefing — renders only PRESENT risks and passes briefing-eval', () => {
  const packet = {
    corp: { name: '테스트자동차(주)', stockCode: '000001', corpCode: '00000001' },
    year: 2025,
    fsDiv: 'CFS',
    macro: { indicator: '한국은행 기준금리', latest: { time: '20260707', value: 2.5 }, unit: '%' },
    newsSignals: null,
    signals: [
      {
        id: 'E1',
        name: '수익-채권 괴리 (이익의 현금화 저조)',
        present: true,
        evidence: {
          revenueGrowthPct: 6.3,
          receivablesGrowthPct: 45.6,
          gapPp: 39.3,
          receivablesTrillion: '5.9→8.6조',
          ocfToOperatingIncome: -0.52,
        },
        markers: ['(?<!\\d)6[.,]3(?!\\d)', '(?<!\\d)45[.,]6(?!\\d)', '매출채권', '현금흐름|현금화'],
        categoryPattern: '수익|채권|현금화',
        checkHints: ['분기보고서로 채권 증가 시점 좁히기', '대손충당금 설정률 추이'],
      },
      {
        id: 'E2',
        name: '재고 자산 적체',
        present: false,
        evidence: { revenueGrowthPct: 6.3, inventoryGrowthPct: 4.4 },
        markers: [],
        categoryPattern: '재고',
        checkHints: ['재고자산 평가손실 확인'],
      },
    ],
  };

  const briefing = renderLocalBriefing(packet, { companyName: '테스트자동차' });
  assert.match(briefing, /수익-채권 괴리/);
  assert.doesNotMatch(briefing, /^## .*재고 자산 적체/m);
  assert.match(briefing, /본 자료는 CEO 경영 리스크 분석 보조 자료/);

  const result = evaluateBriefing(briefing, { signals: signalsFromPacket(packet) });
  assert.equal(result.pass, true, JSON.stringify(result, null, 2));
});
