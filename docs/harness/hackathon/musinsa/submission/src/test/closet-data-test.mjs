#!/usr/bin/env node
/**
 * 샘플 옷장 데이터 정합성 테스트 — README-data.md 정답지의 수치가 CSV 에서
 * 실제로 재계산되는지 검증한다 (정답지 드리프트 방지).
 * 실행: node src/test/closet-data-test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createAssert } from './smoke-harness.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const assert = createAssert();

function parseCsv(path) {
  const [header, ...lines] = readFileSync(path, 'utf8').trim().split(/\r?\n/);
  const cols = header.split(',');
  return lines.map((line) => {
    const values = line.split(',');
    return Object.fromEntries(cols.map((c, i) => [c, values[i] ?? '']));
  });
}

const closet = parseCsv(join(here, '..', 'data', 'sample', 'closet.csv'));
const purchases = parseCsv(join(here, '..', 'data', 'sample', 'purchases.csv'));

assert.check('closet 16벌', closet.length === 16, `got ${closet.length}`);
assert.check('purchases 18건', purchases.length === 18, `got ${purchases.length}`);

// 패턴 1 — 세일 충동구매 → 미착용
const impulse = closet.filter((i) => Number(i.discount_pct) >= 55);
const impulsePaid = impulse.reduce((sum, i) => sum + Number(i.price_paid), 0);
const impulseWorn = impulse.reduce((sum, i) => sum + Number(i.worn_count_6mo), 0);
assert.check('패턴1: 할인 55%+ 정확히 4건', impulse.length === 4);
assert.check('패턴1: 지출 합계 161,000원', impulsePaid === 161_000, `got ${impulsePaid}`);
assert.check('패턴1: 착용 합계 2회', impulseWorn === 2, `got ${impulseWorn}`);
const impulseOrders = purchases.filter((p) => Number(p.discount_pct) >= 55);
assert.check('패턴1: 구매 내역에서 전부 trigger=세일알림', impulseOrders.length === 4 && impulseOrders.every((p) => p.trigger === '세일알림'));
const hero = closet.find((i) => i.item_id === 'C01');
assert.check('패턴1 대조군: C01 착용당 비용 < 2,000원', Number(hero.price_paid) / Number(hero.worn_count_6mo) < 2000);

// 패턴 2 — 블랙 상의 편중
const tops = closet.filter((i) => i.category === '상의');
const blackTops = tops.filter((i) => i.color === '블랙');
const blackHoodSweat = blackTops.filter((i) => ['후드', '스웨트셔츠'].includes(i.subcategory));
assert.check('패턴2: 상의 12벌', tops.length === 12);
assert.check('패턴2: 블랙 상의 7벌 (58%)', blackTops.length === 7);
assert.check('패턴2: 블랙 후드·스웨트 5벌', blackHoodSweat.length === 5);
assert.check('패턴2: 충동 4건 중 블랙 상의 3건', impulse.filter((i) => i.color === '블랙' && i.category === '상의').length === 3);

// 패턴 3 — 테일러독 사이즈 반품 루프
const tailordog = purchases.filter((p) => p.brand === '테일러독');
assert.check('패턴3: 테일러독 구매 3회 전부 L', tailordog.length === 3 && tailordog.every((p) => p.size === 'L'));
assert.check('패턴3: 반품 2회, 사유 전부 사이즈 작음',
  tailordog.filter((p) => p.returned === 'Y').length === 2
  && tailordog.filter((p) => p.returned === 'Y').every((p) => p.return_reason === '사이즈 작음'));
const kept = closet.find((i) => i.brand === '테일러독');
assert.check('패턴3: 보유 1벌은 착용 3회 방치', kept && Number(kept.worn_count_6mo) === 3);

// 교차 정합성 — 옷장의 모든 아이템은 반품 아닌 구매 기록과 1:1 대응
const orderKey = (r) => `${r.brand}|${r.item_name}|${r.color}`;
const keptOrders = new Set(purchases.filter((p) => p.returned === 'N').map(orderKey));
assert.check('교차: 옷장 16벌 전부 구매 기록 존재', closet.every((i) => keptOrders.has(orderKey(i))));
assert.check('교차: 반품 아닌 구매 16건 = 옷장 16벌', purchases.filter((p) => p.returned === 'N').length === closet.length);
// 금액 필드 정합: price_paid = list_price × (1 - discount/100) (반올림 50원 단위 허용)
assert.check('금액: 할인 계산 정합', closet.every((i) => Math.abs(Number(i.list_price) * (1 - Number(i.discount_pct) / 100) - Number(i.price_paid)) < 51));

assert.finish();
