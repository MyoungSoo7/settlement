import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

import {
  collectAudit,
  extractHarnessContract,
  parseDocLinks,
  parseDockerfileModules,
  parseGatewayRouteIds,
  parseScanBasePackages,
  readContractCases,
  runAuditCli,
  validateManifest,
} from '../harness-audit.mjs';
import {
  compareOntologies,
  deriveInterviewHarnessTransition,
  parseInterviewHarnessDoc,
  runInterviewHarnessCli,
} from '../interview-harness.mjs';

const FACTS = { maxCycles: 5, canonicalScratch: '.symposium/scratch/socrates.md' };
const CASES = [
  ['seed-gate-create', 'incomplete-seed->socrates'],
  ['seed-gate-reuse', 'complete-seed->evolve-step'],
  ['user-adoption', 'candidate-requires-explicit-user-approval'],
  ['first-cycle-skip', 'cycle-1->skip-comparison'],
  ['threshold-boundary', 'similarity>=0.85->convergence'],
  ['safety-cycle-5', 'cycle-5-not-converged->safety_valve'],
];
const CASE_MAP = Object.fromEntries(CASES);

function baseManifest(files = []) {
  return {
    schemaVersion: 1,
    requiredTrackedFiles: files,
    criticalContractPairs: [],
  };
}

function git(root, ...args) {
  return execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' });
}

function put(root, path, content = '') {
  const full = join(root, ...path.split('/'));
  mkdirSync(join(full, '..'), { recursive: true });
  writeFileSync(full, content);
}

function repo(files, tracked = Object.keys(files)) {
  const root = mkdtempSync(join(tmpdir(), 'harness-audit-'));
  git(root, 'init', '-q');
  git(root, 'config', 'user.email', 'audit@example.test');
  git(root, 'config', 'user.name', 'Audit Test');
  for (const [path, content] of Object.entries(files)) put(root, path, content);
  if (tracked.length) {
    git(root, 'add', '--', ...tracked);
    git(root, 'commit', '-qm', 'fixture');
  }
  return root;
}

function assertPushBeforeFailsClosed(workflow) {
  const pushBranch = workflow.slice(workflow.indexOf('BEFORE="${{ github.event.before }}"'));
  assert.match(pushBranch,
    /if \[\[ ! "\$BEFORE" =~ \^\[0-9a-fA-F\]\{40\}\$ \]\]; then\s+echo "invalid push before SHA: \$BEFORE" >&2\s+exit 1\s+fi/);
  const beforeValidation = pushBranch.indexOf('[[ ! "$BEFORE" =~ ^[0-9a-fA-F]{40}$ ]]');
  const zeroShaBranch = pushBranch.indexOf('[ "$BEFORE" = "0000000000000000000000000000000000000000" ]');
  const beforeLookup = pushBranch.indexOf('git cat-file -e "$BEFORE^{commit}"');
  assert.ok(beforeValidation < zeroShaBranch && zeroShaBranch < beforeLookup,
    'invalid revisions must fail before zero-SHA handling and git lookup');
}

test('validateManifest accepts schema 1 and rejects invalid versions and paths', () => {
  assert.deepEqual(validateManifest(baseManifest(['a/b'])), baseManifest(['a/b']));
  for (const value of [
    { ...baseManifest(), schemaVersion: 2 },
    baseManifest(['a', 'a']),
    baseManifest(['/absolute']),
    baseManifest(['a/../b']),
    baseManifest(['a\\b']),
    baseManifest(['']),
  ]) assert.throws(() => validateManifest(value), /manifest/i);
});

test('validateManifest rejects missing contract files and malformed pair paths', () => {
  const pair = { claude: 'a.md', codex: 'b.md', contract: 'x', facts: {} };
  assert.throws(() => validateManifest({ ...baseManifest(['a.md']), criticalContractPairs: [pair] }), /manifest/i);
  assert.throws(() => validateManifest({ ...baseManifest(['a.md', 'b.md']), criticalContractPairs: [{ ...pair, codex: '..\\b' }] }), /manifest/i);
});

test('validateManifest requires the canonical six contract transition mappings', () => {
  const files = ['a.json', 'b.json'];
  const pair = { claude: 'a.json', codex: 'b.json', contract: 'tests', contractCases: CASE_MAP };
  assert.deepEqual(validateManifest({ ...baseManifest(files), criticalContractPairs: [pair] }).criticalContractPairs[0], pair);
  assert.throws(() => validateManifest({ ...baseManifest(files), criticalContractPairs: [{ ...pair, contractCases: { ...CASE_MAP, 'seed-gate-create': '' } }] }), /canonical contract cases/i);
  assert.throws(() => validateManifest({ ...baseManifest(files), criticalContractPairs: [{ ...pair, contractCases: Object.fromEntries(CASES.slice(1)) }] }), /canonical contract cases/i);
});

test('contract readers require one valid block and six unique cases', () => {
  const block = '```harness-contract\n{"maxCycles":5}\n```';
  assert.deepEqual(extractHarnessContract(block), { maxCycles: 5 });
  assert.throws(() => extractHarnessContract('none'), /harness-contract/i);
  assert.throws(() => extractHarnessContract(`${block}\n${block}`), /harness-contract/i);
  assert.throws(() => extractHarnessContract('```harness-contract\n{bad}\n```'), /JSON/i);
  assert.deepEqual(readContractCases(JSON.stringify(CASES.map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })))), Object.fromEntries(CASES));
  assert.throws(() => readContractCases(JSON.stringify(CASES.slice(1).map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })))), /seed-gate-create/);
  assert.throws(() => readContractCases(JSON.stringify([...CASES, ['extra', 'x']].map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })))), /unexpected contract case/i);
});

test('interview harness parser reads seed and ontology sections', () => {
  const doc = parseInterviewHarnessDoc([
    '## Seed',
    '```yaml',
    'goal: close the gap',
    'constraints:',
    '  - keep it reproducible',
    '  - keep it automated',
    'acceptance_criteria:',
    '  - node --test passes',
    '```',
    '## Ontology',
    '```yaml',
    'idea: interview harness',
    'boundary: explicit user approval',
    'properties:',
    '  - deterministic',
    '  - auditable',
    '```',
  ].join('\n'));
  assert.equal(doc.seedComplete, true);
  assert.equal(doc.ontologyComplete, true);
  assert.deepEqual(doc.seed.goal, 'close the gap');
  assert.deepEqual(doc.seed.constraints, ['keep it reproducible', 'keep it automated']);
  assert.deepEqual(doc.ontology.properties, ['deterministic', 'auditable']);
});

test('interview harness ontology comparison uses exact idea, user-confirmed boundary, and jaccard properties', () => {
  assert.deepEqual(compareOntologies(
    { idea: 'interview harness', properties: ['a', 'b'] },
    { idea: 'interview harness', properties: ['b', 'c'] },
    { boundaryConfirmed: true },
  ), {
    idea: 1,
    boundary: 1,
    properties: 1 / 3,
    similarity: (1 + 1 + (1 / 3)) / 3,
  });
});

test('interview harness transitions follow seed gate, cycle skip, threshold, and safety valve rules', () => {
  assert.deepEqual(
    deriveInterviewHarnessTransition({ phase: 'seed-gate', seedComplete: false }),
    {
      phase: 'seed-gate',
      cycle: 0,
      nextStep: 'socrates',
      stopReason: null,
      comparison: null,
    },
  );
  assert.deepEqual(
    deriveInterviewHarnessTransition({ phase: 'seed-gate', seedComplete: true }),
    {
      phase: 'cycle',
      cycle: 1,
      nextStep: 'evolve-step',
      stopReason: null,
      comparison: null,
    },
  );
  assert.deepEqual(
    deriveInterviewHarnessTransition({ phase: 'ontology', cycle: 1 }),
    {
      phase: 'cycle',
      cycle: 2,
      nextStep: 'evolve-step',
      stopReason: null,
      comparison: { skipped: true, reason: 'first-cycle-skip' },
    },
  );
  assert.deepEqual(
    deriveInterviewHarnessTransition({
      phase: 'compare',
      cycle: 2,
      threshold: 0.85,
      boundaryConfirmed: true,
      previousOntology: { idea: 'interview harness', properties: ['deterministic', 'auditable'] },
      currentOntology: { idea: 'interview harness', properties: ['deterministic', 'auditable'] },
    }),
    {
      phase: 'stop',
      cycle: 2,
      nextStep: 'stop',
      stopReason: 'convergence',
      comparison: { idea: 1, boundary: 1, properties: 1, similarity: 1 },
    },
  );
  assert.deepEqual(
    deriveInterviewHarnessTransition({
      phase: 'compare',
      cycle: 5,
      threshold: 0.85,
      previousOntology: { idea: 'a', properties: ['x'] },
      currentOntology: { idea: 'b', properties: ['y'] },
    }),
    {
      phase: 'stop',
      cycle: 5,
      nextStep: 'stop',
      stopReason: 'safety_valve',
      comparison: { idea: 0, boundary: 0, properties: 0, similarity: 0 },
    },
  );
});

test('interview harness CLI is injectable and derives state from scratch files', async () => {
  const root = repo({
    '.symposium/scratch/socrates.md': [
      '## Seed',
      '```yaml',
      'goal: close the gap',
      'constraints:',
      '  - keep it reproducible',
      'acceptance_criteria:',
      '  - node --test passes',
      '```',
      '## Ontology',
      '```yaml',
      'idea: interview harness',
      'boundary: explicit user approval',
      'properties:',
      '  - deterministic',
      '```',
    ].join('\n'),
    '.symposium/scratch/interview-harness.md': [
      '## Ontology',
      '```yaml',
      'idea: interview harness',
      'boundary: explicit user approval',
      'properties:',
      '  - deterministic',
      '```',
    ].join('\n'),
  }, []);
  const output = [];
  assert.equal(await runInterviewHarnessCli(['--root', root, '--phase', 'seed-gate'], { stdout: (s) => output.push(s) }), 0);
  const parsed = JSON.parse(output.join(''));
  assert.equal(parsed.nextStep, 'evolve-step');
  assert.equal(parsed.phase, 'cycle');
  assert.equal(parsed.cycle, 1);
  assert.equal(parsed.seedComplete, undefined);
  const errors = [];
  assert.equal(await runInterviewHarnessCli(['--unknown'], { stderr: (s) => errors.push(s) }), 1);
  assert.match(errors.join(''), /unsupported argument/i);
});

test('collectAudit uses tracked files only and reports missing and untracked required files', () => {
  const root = repo({ 'tracked.txt': 'yes', 'untracked.txt': 'no' }, ['tracked.txt']);
  const result = collectAudit(root, baseManifest(['tracked.txt', 'untracked.txt', 'missing.txt']));
  assert.deepEqual(result.inventory.trackedFiles, ['tracked.txt']);
  assert.match(result.errors.join('\n'), /untracked\.txt.*not tracked/i);
  assert.match(result.errors.join('\n'), /missing\.txt.*missing/i);
});

test('collectAudit validates command-script, settings-hook, STATUS claims, and inventory', () => {
  const status = [
    'application.yml **1개**',
    'migration **1개**',
    'ADR **1개**',
    'test classes **1개**',
  ].join('\n');
  const files = {
    '.claude/commands/check.md': 'node scripts/harness/tool.mjs\nsh scripts/harness/install.sh',
    '.claude/settings.json': '{"command":"node \\"$CLAUDE_PROJECT_DIR/scripts/harness/tool.mjs\\""}',
    'scripts/harness/tool.mjs': '',
    'scripts/harness/install.sh': '',
    'svc/src/main/resources/application.yml': '',
    'svc/src/main/resources/db/migration/V1__x.sql': '',
    'docs/adr/0001-x.md': '',
    'svc/src/test/java/XTest.java': '',
    'STATUS.md': status,
  };
  const root = repo(files);
  const ok = collectAudit(root, baseManifest(Object.keys(files)));
  assert.deepEqual(ok.errors, []);
  assert.deepEqual(ok.inventory, { trackedFiles: Object.keys(files).sort(), agents: 0, skills: 0, commands: 1 });

  put(root, '.claude/commands/check.md', 'node scripts/harness/gone.mjs\nsh scripts/harness/gone.sh');
  put(root, '.claude/settings.json', '{"command":"node \\"$CLAUDE_PROJECT_DIR/scripts/harness/gone.mjs\\""}');
  put(root, 'STATUS.md', status.replace('application.yml **1개**', 'application.yml **9개**'));
  const bad = collectAudit(root, baseManifest(Object.keys(files)));
  assert.match(bad.errors.join('\n'), /gone\.mjs/);
  assert.match(bad.errors.join('\n'), /gone\.sh/);
  assert.match(bad.errors.join('\n'), /application\.yml.*claimed=9.*actual=1/i);
});

test('collectAudit cross-checks gradle module roster against CLAUDE.md and STRUCTURE.md trees', () => {
  const settings = 'include(\n    "alpha-service",\n    "gateway-service",\n)\n\nincludeBuild("shared-common")\n';
  const files = {
    'settings.gradle.kts': settings,
    'CLAUDE.md': '├── alpha-service/ # A\n└── gateway-service/ # GW\n',
    // 구조 정본은 docs/ 아래에 있다 — 루트 'STRUCTURE.md' 로 조회하면 trackedSet 이 항상 빗나가
    // 이 문서가 조용히 검사 대상에서 빠진다(실제로 그렇게 방치돼 있었다).
    'docs/STRUCTURE.md': '├── alpha-service/\n└── gateway-service/\n',
  };
  const root = repo(files);
  assert.deepEqual(collectAudit(root, baseManifest(Object.keys(files))).errors, []);

  put(root, 'CLAUDE.md', '└── gateway-service/ # alpha 누락\n');
  const bad = collectAudit(root, baseManifest(Object.keys(files))).errors.join('\n');
  assert.match(bad, /CLAUDE\.md module roster missing: alpha-service/);
  assert.doesNotMatch(bad, /STRUCTURE\.md/);
  assert.doesNotMatch(bad, /shared-common/);

  // docs/STRUCTURE.md 도 실제로 검사된다 — 경로 오타로 이 검사가 죽으면 여기서 잡힌다.
  put(root, 'docs/STRUCTURE.md', '└── gateway-service/\n');
  const both = collectAudit(root, baseManifest(Object.keys(files))).errors.join('\n');
  assert.match(both, /docs\/STRUCTURE\.md module roster missing: alpha-service/);
});

test('collectAudit cross-checks settlement 서브도메인 roster against 문서 트리 줄', () => {
  const files = {
    'settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Settlement.java': '',
    'settlement-service/src/main/java/github/lms/lemuel/tax/domain/TaxCalculation.java': '',
    // 루트 직속 파일은 서브도메인이 아니다(디렉터리만 대상).
    'settlement-service/src/main/java/github/lms/lemuel/SettlementServiceApplication.java': '',
    // 첫 줄은 가드레일 문단을 흉내낸 미끼 — 'settlement-service/' 만으로 앵커를 잡으면 여기 걸려
    // 트리 줄을 못 보고 전부 누락으로 오탐한다(포트 8082 를 함께 요구해 고정).
    'CLAUDE.md': '- settlement-service/build.gradle.kts 에 order 의존 금지\n'
      + '├── settlement-service/ # Settlement (8082) — settlement·tax\n',
    'docs/STRUCTURE.md': '├── settlement-service/ # Settlement (8082)\n'
      + '│   └── .../{settlement,tax}\n',
  };
  const root = repo(files);
  assert.deepEqual(collectAudit(root, baseManifest(Object.keys(files))).errors, []);

  put(root, 'CLAUDE.md', '- settlement-service/build.gradle.kts 에 order 의존 금지\n'
    + '├── settlement-service/ # Settlement (8082) — settlement\n');
  const bad = collectAudit(root, baseManifest(Object.keys(files))).errors.join('\n');
  assert.match(bad, /CLAUDE\.md settlement 서브도메인 누락: tax/);
  assert.doesNotMatch(bad, /누락: settlement /);
  assert.doesNotMatch(bad, /SettlementServiceApplication/);
});

test('collectAudit resolves HARNESS.md routing map entrypoints against agents, skills, and commands', () => {
  const harness = [
    '> | 정산 로직 | 📘`skill-a`+`skill-b` → 🤖`agent-a` |',
    '> | 진단 | ⌘`/cmd-a` → 🚦`guard.mjs` 셀프체크 |',
    '아이콘 없는 줄의 `not-checked` 토큰과 `{서비스}-rules` 플레이스홀더는 무시된다',
  ].join('\n');
  const files = {
    'HARNESS.md': harness,
    '.claude/agents/agent-a.md': '',
    '.claude/skills/skill-a/SKILL.md': '',
    '.claude/skills/skill-b/SKILL.md': '',
    '.claude/commands/cmd-a.md': '',
  };
  const root = repo(files);
  assert.deepEqual(collectAudit(root, baseManifest(Object.keys(files))).errors, []);

  put(root, 'HARNESS.md', `${harness}\n> | 신규 | 📘\`ghost-skill\` → ⌘\`/ghost-cmd\` |`);
  const bad = collectAudit(root, baseManifest(Object.keys(files))).errors.join('\n');
  assert.match(bad, /HARNESS\.md routing map dangling: ghost-skill/);
  assert.match(bad, /HARNESS\.md routing map dangling: \/ghost-cmd/);
  assert.doesNotMatch(bad, /guard\.mjs/);
  assert.doesNotMatch(bad, /not-checked/);
  assert.doesNotMatch(bad, /skill-a|skill-b|agent-a|cmd-a/);
});

test('routing map slash tokens resolve only to commands, not skills or agents', () => {
  const files = {
    'HARNESS.md': '> | 온콜 | ⌘`/only-skill-exists` |',
    '.claude/skills/only-skill-exists/SKILL.md': '',
  };
  const root = repo(files);
  assert.match(
    collectAudit(root, baseManifest(Object.keys(files))).errors.join('\n'),
    /routing map dangling: \/only-skill-exists/,
  );
});

test('collectAudit deep-compares both contract blocks and transition cases', () => {
  const skill = (facts) => `prose ignored\n\`\`\`harness-contract\n${JSON.stringify(facts)}\n\`\`\``;
  const cases = JSON.stringify(CASES.map(([contractCase, expectedTransition]) => ({ contractCase, expectedTransition })));
  const files = { 'a.md': skill(FACTS), 'b.md': skill(FACTS), 'a.json': cases, 'b.json': cases };
  const manifest = {
    ...baseManifest(Object.keys(files)),
    criticalContractPairs: [
      { claude: 'a.md', codex: 'b.md', contract: 'skills', facts: FACTS },
      { claude: 'a.json', codex: 'b.json', contract: 'tests', contractCases: CASE_MAP },
    ],
  };
  const root = repo(files);
  assert.deepEqual(collectAudit(root, manifest).errors, []);
  put(root, 'b.md', skill({ ...FACTS, maxCycles: 6 }));
  assert.match(collectAudit(root, manifest).errors.join('\n'), /facts mismatch/i);
});

test('collectAudit rejects identical wrong transitions against manifest mappings', () => {
  const wrong = JSON.stringify(CASES.map(([contractCase, expectedTransition]) => ({
    contractCase,
    expectedTransition: contractCase === 'user-adoption' ? '' : expectedTransition,
  })));
  const files = { 'a.json': wrong, 'b.json': wrong };
  const manifest = {
    ...baseManifest(Object.keys(files)),
    criticalContractPairs: [{ claude: 'a.json', codex: 'b.json', contract: 'tests', contractCases: CASE_MAP }],
  };
  const result = collectAudit(repo(files), manifest);
  assert.match(result.errors.join('\n'), /tests.*claude.*contract cases mismatch/i);
  assert.match(result.errors.join('\n'), /tests.*codex.*contract cases mismatch/i);
});

test('runAuditCli is injectable and returns a status without exiting the importer', async () => {
  const manifest = baseManifest(['manifest.json']);
  const root = repo({ 'manifest.json': JSON.stringify(manifest) });
  const output = [];
  assert.equal(await runAuditCli(['--root', root, '--manifest', 'manifest.json'], { stdout: (s) => output.push(s) }), 0);
  assert.match(output.join(''), /harness-audit: healthy/i);
  const errors = [];
  assert.equal(await runAuditCli(['--unknown'], { stderr: (s) => errors.push(s) }), 1);
  assert.match(errors.join(''), /unsupported argument/i);
});

test('harness guard workflow uses deterministic bases and ordered reproducibility checks', () => {
  const workflow = readFileSync(join(process.cwd(), '.github/workflows/harness-guard.yml'), 'utf8');
  assert.match(workflow, /fetch-depth:\s*0/);
  assert.match(workflow, /node-version:\s*['"]?22['"]?/);
  assert.match(workflow, /--diff-filter=ACMR/);
  assert.match(workflow, /git fetch --no-tags origin "\+refs\/heads\/\$\{BASE_REF\}:refs\/remotes\/origin\/\$\{BASE_REF\}"/);
  assert.match(workflow, /git merge-base HEAD "refs\/remotes\/origin\/\$BASE_REF"/);
  assert.match(workflow, /git cat-file -e "\$BASE\^\{commit\}"/);
  assert.match(workflow, /git cat-file -e "\$BEFORE\^\{commit\}"/);
  assertPushBeforeFailsClosed(workflow);
  for (const invalidBefore of ['', 'abc', 'g'.repeat(40), 'a'.repeat(41)]) {
    assert.equal(/^[0-9a-fA-F]{40}$/.test(invalidBefore), false);
  }
  assert.throws(() => assertPushBeforeFailsClosed(workflow.replace(
    'echo "invalid push before SHA: $BEFORE" >&2\n              exit 1',
    'echo "invalid push before SHA: $BEFORE" >&2\n              exit 0',
  )));
  assert.match(workflow, /git ls-files > changed\.txt/);
  assert.match(workflow, /unsupported event/i);
  assert.doesNotMatch(workflow, /HEAD~1|\|\|\s*git diff/);

  const ordered = [
    'Run harness tests',
    'Compute changed files',
    'Money / architecture guard (changed files)',
    'Harness self-audit',
    'Verify manifest tracking',
    'Verify clean working tree',
  ].map((name) => workflow.indexOf(name));
  assert.ok(ordered.every((index) => index >= 0));
  assert.deepEqual(ordered, [...ordered].sort((a, b) => a - b));
  assert.match(workflow, /node --test scripts\/harness\/test\/\*\.test\.mjs/);
  assert.match(workflow, /git diff --exit-code/);
});

test('Claude settings retain the write guard, advisory skill router, and telemetry session hooks', () => {
  const settings = JSON.parse(readFileSync(join(process.cwd(), '.claude/settings.json'), 'utf8'));
  assert.deepEqual(settings, {
    hooks: {
      PreToolUse: [
        {
          matcher: 'Write|Edit|MultiEdit',
          hooks: [{
            type: 'command',
            command: 'node "$CLAUDE_PROJECT_DIR/scripts/harness/guard.mjs" --hook',
          }],
        },
        {
          matcher: 'Write|Edit|MultiEdit|Skill',
          hooks: [{
            type: 'command',
            command: 'node "$CLAUDE_PROJECT_DIR/scripts/harness/skill-router.mjs" --hook',
          }],
        },
      ],
      SessionStart: [
        {
          hooks: [{
            type: 'command',
            command: 'node "$CLAUDE_PROJECT_DIR/scripts/harness/telemetry-report.mjs" --hook',
          }],
        },
      ],
    },
  });
});

test('parseDocLinks collects markdown link targets and skips non-path links', () => {
  const md = [
    '[tracked](docs/a.md) [dir](docs/) [anchor](#section)',
    '[ext](https://example.com/x) [mail](mailto:a@b.c)',
    '[title](docs/b.md "제목") [placeholder](docs/<name>.md)',
    '[query](docs/c.md#frag) `[notalink](x)` 는 인라인 코드가 아니라 링크로 본다',
  ].join('\n');
  assert.deepEqual(parseDocLinks(md), ['docs/a.md', 'docs/', 'docs/b.md', 'docs/c.md#frag', 'x']);
});

// 문서 → 저장소 노드 간선. 오늘의 사고 유형 그대로:
// 삭제된 경로를 가리키는 링크(완전 없음)와, 디스크에는 있지만 추적되지 않는 경로를 "정본"으로
// 가리키는 링크(로컬만 존재)를 구분해 보고한다. 후자가 더 위험하다 — 작성자 화면에서는 열리고
// clone 한 사람에게만 깨지므로 육안 리뷰로는 잡히지 않는다.
test('collectAudit reports doc links that leave the tracked graph', () => {
  const files = {
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    'README.md': [
      '[ok-file](docs/kept.md)',
      '[ok-dir](docs/)',
      '[gone](docs/deleted.md)',
      '[local-only](docs/local.md)',
      '[external](https://example.com)',
      '[anchor](#top)',
    ].join('\n'),
    'docs/kept.md': '# kept',
    'docs/local.md': '# 디스크에는 있지만 추적되지 않는다',
  };
  const root = repo(files, ['manifest.json', 'README.md', 'docs/kept.md']);
  const errors = collectAudit(root, baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('doc link'));

  assert.equal(errors.length, 2, errors.join('\n'));
  assert.match(errors.join('\n'), /doc link dangling: README\.md → docs\/deleted\.md/);
  assert.match(errors.join('\n'), /doc link untracked: README\.md → docs\/local\.md.*로컬/);
});

// 에이전트 지시서(.claude/·.codex/)의 링크는 저장소 참조가 아니라 "이런 파일을 만들어라"는
// 산출물 명세인 경우가 많다(ai-dev-team 커맨드의 산출물 목차 등). 여기까지 검사하면 템플릿마다
// 예외를 등록하게 되고, 예외 목록이 길어지면 검사 자체가 의미를 잃는다. 이 트리의 참조 무결성은
// 별도 검사(broken reference in ...)가 scripts/harness 경로에 대해 이미 담당한다.
test('collectAudit skips agent instruction trees — their links are output specs', () => {
  const root = repo({
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    '.claude/commands/agents/docs.md': '[산출물](docs/generated/01-output.json)',
    '.codex/skills/x/SKILL.md': '[산출물](docs/generated/02-output.md)',
  });
  assert.deepEqual(collectAudit(root, baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('doc link')), []);
});

test('collectAudit honours manifest docLinkIgnorePrefixes', () => {
  const manifest = { ...baseManifest(['manifest.json']), docLinkIgnorePrefixes: ['docs/generated/'] };
  const root = repo({
    'manifest.json': JSON.stringify(manifest),
    'README.md': '[runtime output](docs/generated/report.md) [gone](docs/deleted.md)',
  });
  const errors = collectAudit(root, manifest).errors.filter((e) => e.startsWith('doc link'));
  assert.deepEqual(errors.map((e) => e.replace(/ \(.*/, '')), ['doc link dangling: README.md → docs/deleted.md']);
});

test('wiring parsers read Dockerfile copies, gateway route ids, and scanned packages', () => {
  assert.deepEqual(parseDockerfileModules([
    'COPY order-service/build.gradle.kts ./order-service/',
    'COPY order-service ./order-service',
    'COPY card-service/build.gradle.kts ./card-service/',
  ].join('\n')), ['order-service']); // 의존 COPY 만 있고 소스 COPY 가 없으면 배선된 것이 아니다
  assert.deepEqual(parseGatewayRouteIds('routes:\n  - id: order-service-orders\n    uri: x\n  - id: card-service\n'),
    ['order-service-orders', 'card-service']);
  assert.deepEqual(parseScanBasePackages('@SpringBootApplication(scanBasePackages = {"github.lms.lemuel.order", "github.lms.lemuel.common"})'),
    ['github.lms.lemuel.order', 'github.lms.lemuel.common']);
  assert.deepEqual(parseScanBasePackages('@SpringBootApplication(scanBasePackages = "github.lms.lemuel")'), ['github.lms.lemuel']);
  assert.deepEqual(parseScanBasePackages('@SpringBootApplication'), []);
});

// 서비스 → 배선 간선. 배선 누락은 컴파일이 잡아주지 않고 런타임에 조용히 404/500 으로 나타난다
// (실사고 36ac0234: 코드는 있는데 5곳 미배선으로 프론트 3개 페이지 크래시).
test('collectAudit reports missing module and package wiring', () => {
  const files = {
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    'settings.gradle.kts': 'include(\n  "order-service",\n  "card-service",\n  "gateway-service",\n)',
    'Dockerfile': 'COPY order-service/build.gradle.kts ./order-service/\nCOPY order-service ./order-service\n'
      + 'COPY gateway-service/build.gradle.kts ./gateway-service/\nCOPY gateway-service ./gateway-service\n',
    'gateway-service/src/main/resources/application.yml': 'routes:\n  - id: order-service-orders\n    uri: x\n',
    'order-service/src/main/java/github/lms/lemuel/LemuelApplication.java':
      '@SpringBootApplication(scanBasePackages = {"github.lms.lemuel.order"})\npublic class LemuelApplication {}',
    'order-service/src/main/java/github/lms/lemuel/order/OrderController.java': '@RestController\nclass OrderController {}',
    'order-service/src/main/java/github/lms/lemuel/menu/MenuController.java': '@RestController\nclass MenuController {}',
    'order-service/src/main/java/github/lms/lemuel/web/Marker.java': 'interface Marker {}',
  };
  const errors = collectAudit(repo(files), baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('service wiring'));

  assert.equal(errors.length, 3, errors.join('\n'));
  assert.match(errors.join('\n'), /Dockerfile COPY 누락: card-service/);
  assert.match(errors.join('\n'), /gateway 라우트 누락: card-service/);
  assert.match(errors.join('\n'), /scanBasePackages 누락: order-service → github\.lms\.lemuel\.menu/);
  // 스프링 스테레오타입이 없는 패키지는 스캔 대상이 아니다 — 요구하면 마커·DTO 패키지마다 오탐이 난다
  assert.doesNotMatch(errors.join('\n'), /lemuel\.web/);
});

// 서비스 → 배포 파이프라인 간선. Dockerfile·gateway 가 배선돼 있어도 CI 매트릭스와 compose 에서
// 빠지면 "테스트는 도는데 이미지가 영원히 안 만들어지고 로컬에서 뜨지도 않는" 상태가 된다
// (실사고: deposit-service 가 머지된 뒤 두 곳 모두에서 누락돼 배포 산출물이 없었다).
test('collectAudit reports modules missing from the CI matrix and docker-compose', () => {
  const files = {
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    'settings.gradle.kts': 'include(\n  "order-service",\n  "card-service",\n  "gateway-service",\n)',
    'Dockerfile': 'COPY order-service/build.gradle.kts ./order-service/\nCOPY order-service ./order-service\n'
      + 'COPY card-service/build.gradle.kts ./card-service/\nCOPY card-service ./card-service\n'
      + 'COPY gateway-service/build.gradle.kts ./gateway-service/\nCOPY gateway-service ./gateway-service\n',
    'gateway-service/src/main/resources/application.yml':
      'routes:\n  - id: order-service-orders\n    uri: x\n  - id: card-service\n    uri: y\n',
    '.github/workflows/ci.yml':
      '            order-service: [\'order-service/**\']\n'
      + '            gateway-service: [\'gateway-service/**\']\n'
      + '            mapping=\'{\n              "order-service":"",\n              "gateway-service":"-gateway"\n            }\'\n',
    'docker-compose.yml': 'services:\n  order-service:\n    image: x\n  gateway-service:\n    image: y\n',
  };
  const errors = collectAudit(repo(files), baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('service wiring'));

  assert.match(errors.join('\n'), /CI 매트릭스 누락: card-service/);
  assert.match(errors.join('\n'), /docker-compose 누락: card-service/);
  // 배선된 모듈은 조용하다 — 오탐이 나면 규칙이 무시당한다.
  assert.doesNotMatch(errors.join('\n'), /누락: order-service/);
  assert.doesNotMatch(errors.join('\n'), /누락: gateway-service/);
});

test('collectAudit accepts fully wired modules', () => {
  const files = {
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    'settings.gradle.kts': 'include(\n  "order-service",\n  "gateway-service",\n)',
    'Dockerfile': 'COPY order-service/build.gradle.kts ./order-service/\nCOPY order-service ./order-service\n'
      + 'COPY gateway-service/build.gradle.kts ./gateway-service/\nCOPY gateway-service ./gateway-service\n',
    'gateway-service/src/main/resources/application.yml': 'routes:\n  - id: order-service-orders\n    uri: x\n',
    'order-service/src/main/java/github/lms/lemuel/LemuelApplication.java':
      '@SpringBootApplication(scanBasePackages = {"github.lms.lemuel.order"})\npublic class LemuelApplication {}',
    'order-service/src/main/java/github/lms/lemuel/order/OrderController.java': '@RestController\nclass OrderController {}',
  };
  assert.deepEqual(collectAudit(repo(files), baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('service wiring')), []);
});

// 제출물 → 소유 서비스 간선. CLAUDE.md 배치 기준을 기계로 옮긴다: 소유 서비스가 있는 제출물은
// 그 서비스의 src/main/resources/ 아래에 두고, jar 에는 processResources exclude 로 넣지 않는다.
// 오늘 이 기준이 문서·가드·트리에서 서로 달라 세 번 왕복했다 — 문서 규율로 두면 또 어긋난다.
test('collectAudit reports submissions outside their owning service and unexcluded from the jar', () => {
  const files = {
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    'settings.gradle.kts': `include(
  "order-service",
)`,
    'order-service/build.gradle.kts': `tasks.named<ProcessResources>("processResources") {
}`,
    'order-service/src/main/resources/fashion-copilot/.claude-plugin/plugin.json': '{}',
    'docs/harness/hackathon/kakaopay/submission/src/.codex-plugin/plugin.json': '{}',
  };
  const errors = collectAudit(repo(files), baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('submission'));

  assert.equal(errors.length, 2, errors.join('\n'));
  assert.match(errors.join('\n'), /submission placement: docs[/]harness[/]hackathon[/]kakaopay/);
  assert.match(errors.join('\n'), /submission jar leak: order-service . fashion-copilot/);
});

test('collectAudit accepts a submission owned by a service and excluded from its jar', () => {
  const files = {
    'manifest.json': JSON.stringify(baseManifest(['manifest.json'])),
    'settings.gradle.kts': `include(
  "order-service",
)`,
    'order-service/build.gradle.kts': `tasks.named<ProcessResources>("processResources") {
    exclude("fashion-copilot/**")
}`,
    'order-service/src/main/resources/fashion-copilot/.claude-plugin/plugin.json': '{}',
    'order-service/src/main/resources/fashion-copilot/.codex-plugin/plugin.json': '{}',
  };
  assert.deepEqual(collectAudit(repo(files), baseManifest(['manifest.json'])).errors.filter((e) => e.startsWith('submission')), []);
});

test('repository harness contracts match the tracked manifest oracle', () => {
  const root = process.cwd();
  const manifest = JSON.parse(execFileSync('git', ['-C', root, 'show', ':scripts/harness/manifest.json'], { encoding: 'utf8' }));
  const governedErrors = collectAudit(root, manifest).errors.filter((error) =>
    error.startsWith('STATUS ') || error.startsWith('interview-harness:'),
  );
  for (const path of ['.claude/skills/interview-harness/SKILL.md', '.codex/skills/interview-harness/SKILL.md']) {
    const skill = readFileSync(join(root, ...path.split('/')), 'utf8');
    if (/cycle\s*>\s*5/i.test(skill)) governedErrors.push(`${path}: forbidden cycle > 5 contract wording`);
  }
  assert.deepEqual(governedErrors, []);
  assert.match(execFileSync(process.execPath, ['scripts/harness/harness-audit.mjs'], {
    cwd: root,
    encoding: 'utf8',
  }), /harness-audit: healthy/i);
});

// ── 문서 사실 게이트 3종 ────────────────────────────────────────────────────────
// 2026-08-12 실측 드리프트 3종의 회귀 가드. 각 규칙은 "잡는다" 와 "오탐하지 않는다" 를 짝으로 검증한다.
const DOC_FACTS_SETTINGS = 'include(\n  "card-service",\n  "organization-service",\n  "deposit-service",\n)';

function docFactsRepo(files) {
  return repo({
    'settings.gradle.kts': DOC_FACTS_SETTINGS,
    'shared-common/src/testFixtures/resources/contracts/events/lemuel.organization.created.schema.json': '{}',
    'shared-common/src/testFixtures/resources/contracts/events/lemuel.card.issued.schema.json': '{}',
    ...files,
  });
}

function docFactErrors(files) {
  const root = docFactsRepo(files);
  return collectAudit(root, baseManifest()).errors.filter((error) => error.startsWith('doc facts:'));
}

test('doc facts: 계약 토픽 수 주장이 실제 스키마 파일 수와 다르면 실패한다', () => {
  const errors = docFactErrors({
    'CLAUDE.md': 'card-service organization-service deposit-service\n- contracts/events/ 는 12토픽 JSON Schema 정본이다.\n',
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /CLAUDE\.md:2 이벤트 계약 토픽 수 불일치: claimed=12 actual=2/);
});

test('doc facts: 계약 코퍼스와 무관한 부분집합 토픽 수는 오탐하지 않는다', () => {
  assert.deepEqual(docFactErrors({
    'CLAUDE.md': 'card-service organization-service deposit-service\n- contracts/events/ 는 2토픽 정본이다.\n- account 는 6토픽을 소비한다.\n',
  }), []);
});

test('doc facts: 어댑터가 실재하는데 미구현으로 적히면 실패한다(card 구현 상태 역전 회귀)', () => {
  const errors = docFactErrors({
    'CLAUDE.md': 'organization-service deposit-service\n- card-service/ — REST·스케줄러는 미구현\n',
    'card-service/src/main/java/github/lms/lemuel/card/adapter/in/web/CardController.java': 'class CardController {}',
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /CLAUDE\.md:2 구현 상태 역전: card-service 의 REST\(adapter\/in\/web\)는 실재/);
});

test('doc facts: 어댑터가 실제로 없으면 미배선 기술을 통과시킨다', () => {
  assert.deepEqual(docFactErrors({
    'CLAUDE.md': 'card-service organization-service\n- deposit-service/ — Kafka 컨슈머는 미배선\n',
  }), []);
});

test('doc facts: 다른 모듈이 토픽을 참조하면 "소비처 미배선" 기술은 실패한다(organization 회귀)', () => {
  const errors = docFactErrors({
    'CLAUDE.md': 'card-service deposit-service\n- organization-service — 이벤트 발행 전용(소비처 미배선)\n',
    'organization-service/src/main/resources/application.yml': 'topic:\n  created: lemuel.organization.created\n',
    'card-service/src/main/resources/application.yml': 'topic:\n  organization-created: lemuel.organization.created\n',
  });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /소비처 배선 있음: lemuel\.organization\.created 를 card-service 가 참조/);
});

test('doc facts: 발행 모듈 자신의 yml 참조는 소비 근거로 보지 않는다', () => {
  assert.deepEqual(docFactErrors({
    'SPEC.md': 'organization-service deposit-service\n| `lemuel.card.issued` | card | 소비처 미배선 — 발행 전용 |\n',
    'card-service/src/main/resources/application.yml': 'topic:\n  issued: lemuel.card.issued\n',
  }), []);
});
