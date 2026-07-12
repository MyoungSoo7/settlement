import assert from 'node:assert/strict';
import { describe, test } from 'node:test';

import { parseAllowance, scanText } from '../guard.mjs';

const NOW = new Date('2026-07-13T12:00:00Z');
const marker = (fields = '') => ['harness-guard:', 'allow', fields && ` ${fields}`].join('');
const VALID_ALLOWANCE = marker('reason="bounded migration" issue="ISSUE-123" owner="team-settlement" expires="2026-08-01"');

const cases = [
  {
    id: 'MONEY-PRIMITIVE',
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
    violation: 'double amount = 1.0;',
    normal: 'BigDecimal amount = BigDecimal.ONE;',
  },
  {
    id: 'IMMUTABLE-HISTORY',
    file: 'db/migration/V1__ledger.sql',
    violation: 'UPDATE ledger_entries SET amount = 0;',
    normal: 'INSERT INTO ledger_entries (amount) VALUES (0);',
  },
  {
    id: 'MSA-BOUNDARY',
    file: 'settlement-service/src/main/java/github/lms/lemuel/settlement/App.java',
    violation: 'import github.lms.lemuel.order.domain.Order;',
    normal: 'import github.lms.lemuel.settlement.domain.Settlement;',
  },
  {
    id: 'ACCOUNT-CONSUME-ONLY',
    file: 'account-service/src/main/java/github/lms/lemuel/account/App.java',
    violation: 'kafkaTemplate.send("ledger", event);',
    normal: 'ledgerConsumer.consume(event);',
  },
  {
    id: 'MARKET-NO-VALUATION',
    file: 'market-service/src/main/java/github/lms/lemuel/market/Quote.java',
    violation: 'BigDecimal PBR = price.divide(bookValue);',
    normal: 'BigDecimal marketPrice = quote.price();',
  },
  {
    id: 'NO-COMMIT',
    file: 'pwc/submission/result.json',
    violation: '{}',
    normalFile: 'docs/result.json',
    normal: '{}',
  },
];

describe('guard policy fixtures', () => {
  for (const fixture of cases) {
    test(`${fixture.id} blocks a violation and accepts normal content`, () => {
      const blocked = scanText(fixture.file, fixture.violation, { now: NOW });
      const clean = scanText(fixture.normalFile ?? fixture.file, fixture.normal, { now: NOW });

      assert.deepEqual(blocked.violations.map(({ id }) => id), [fixture.id]);
      assert.deepEqual(clean.violations, []);
    });
  }

  test('ignores Java and Kotlin comment-only lines', () => {
    const java = scanText(cases[0].file, '// double amount = 1.0;', { now: NOW });
    const kotlin = scanText(
      'settlement-service/src/main/kotlin/github/lms/lemuel/settlement/domain/Money.kt',
      '/* float amount = 1.0; */',
      { now: NOW },
    );

    assert.deepEqual(java.violations, []);
    assert.deepEqual(kotlin.violations, []);
  });

  for (const fixture of [
    {
      id: 'MONEY-PRIMITIVE',
      file: String.raw`C:\workspace\repo\settlement-service\src\main\java\github\lms\lemuel\settlement\domain\Money.java`,
      content: 'double amount = 1.0;',
    },
    {
      id: 'MONEY-PRIMITIVE',
      file: '/workspace/repo/settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java',
      content: 'double amount = 1.0;',
    },
    {
      id: 'ACCOUNT-CONSUME-ONLY',
      file: String.raw`C:\workspace\repo\account-service\src\main\java\github\lms\lemuel\account\Publisher.java`,
      content: 'kafkaTemplate.send("ledger", event);',
    },
    {
      id: 'ACCOUNT-CONSUME-ONLY',
      file: '/workspace/repo/account-service/src/main/java/github/lms/lemuel/account/Publisher.java',
      content: 'kafkaTemplate.send("ledger", event);',
    },
    {
      id: 'NO-COMMIT',
      file: String.raw`C:\workspace\repo\pwc\submission\result.json`,
      content: '{}',
    },
    {
      id: 'NO-COMMIT',
      file: '/workspace/repo/pwc/submission/result.json',
      content: '{}',
    },
  ]) {
    test(`${fixture.id} cannot be bypassed with absolute path ${fixture.file}`, () => {
      const result = scanText(fixture.file, fixture.content, { now: NOW });
      assert.ok(result.violations.some(({ id }) => id === fixture.id));
    });
  }
});

describe('structured allowances', () => {
  test('parses an auditable allowance', () => {
    assert.deepEqual(parseAllowance(`// ${VALID_ALLOWANCE}`, { now: NOW }), {
      reason: 'bounded migration',
      issue: 'ISSUE-123',
      owner: 'team-settlement',
      expires: '2026-08-01',
    });
  });

  test('accepts a GitHub issue URL', () => {
    const line = marker('reason="temporary bridge" issue="https://github.com/acme/settlement/issues/42" owner="team-platform" expires="2026-08-01"');
    assert.equal(parseAllowance(line, { now: NOW })?.issue, 'https://github.com/acme/settlement/issues/42');
  });

  for (const [name, allowanceText] of [
    ['bare', marker()],
    ['missing field', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement"')],
    ['extra field', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-08-01" ticket="shadow"')],
    ['blank reason', marker('reason="   " issue="ISSUE-1" owner="team-settlement" expires="2026-08-01"')],
    ['malformed issue', marker('reason="bounded" issue="ADR-1" owner="team-settlement" expires="2026-08-01"')],
    ['malformed owner', marker('reason="bounded" issue="ISSUE-1" owner="alice" expires="2026-08-01"')],
    ['invalid date', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-02-30"')],
    ['expired', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-07-12"')],
    ['expires today', marker('reason="bounded" issue="ISSUE-1" owner="team-settlement" expires="2026-07-13"')],
  ]) {
    test(`rejects ${name} allowance`, () => {
      assert.equal(parseAllowance(allowanceText, { now: NOW }), null);
      const result = scanText(cases[0].file, `double amount = 1.0; // ${allowanceText}`, { now: NOW });
      assert.ok(result.violations.some(({ id }) => id === 'INVALID-ALLOWANCE'));
      assert.ok(result.violations.some(({ id }) => id === 'MONEY-PRIMITIVE'));
    });
  }

  test('a valid allowance suppresses only its own violating line and is reported', () => {
    const result = scanText(
      cases[0].file,
      `double first = 1.0; // ${VALID_ALLOWANCE}\ndouble second = 2.0;`,
      { now: NOW },
    );

    assert.deepEqual(result.violations.map(({ id, line }) => ({ id, line })), [
      { id: 'MONEY-PRIMITIVE', line: 2 },
    ]);
    assert.deepEqual(result.allowances, [{
      file: cases[0].file,
      line: 1,
      reason: 'bounded migration',
      issue: 'ISSUE-123',
      owner: 'team-settlement',
      expires: '2026-08-01',
    }]);
  });
});
