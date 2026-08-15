import assert from "node:assert/strict";
import { afterEach, describe, test } from "node:test";
import { mkdtemp, mkdir, readFile, rm, utimes, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  decideHookOutput,
  pruneStaleState,
  routeSkills,
  runRouterCli,
} from "../skill-router.mjs";
import {
  appendJsonl,
  guardHitRecords,
  LOG_DIR_SEGMENTS,
} from "../telemetry.mjs";
import { runGuardCli } from "../guard.mjs";
import { readJsonl, summarize } from "../telemetry-report.mjs";

const temporaryDirectories = [];
async function temporaryRepo() {
  const directory = await mkdtemp(join(tmpdir(), "skill-router-test-"));
  temporaryDirectories.push(directory);
  return directory;
}
afterEach(async () => {
  await Promise.all(
    temporaryDirectories
      .splice(0)
      .map((directory) => rm(directory, { recursive: true, force: true })),
  );
});

describe("routeSkills", () => {
  test("settlement ledger source routes ledger-invariants first", () => {
    assert.deepEqual(
      routeSkills(
        "settlement-service/src/main/java/github/lms/lemuel/settlement/ledger/domain/LedgerEntry.java",
      ),
      ["ledger-invariants", "settlement-domain-rules", "tdd-discipline"],
    );
  });
  test("service sources route to their *-rules skill plus tdd-discipline last", () => {
    assert.deepEqual(
      routeSkills(
        "order-service/src/main/java/github/lms/lemuel/order/domain/Order.java",
      ),
      ["order-commerce-rules", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "account-service/src/main/java/github/lms/lemuel/account/domain/JournalEntry.java",
      ),
      ["account-domain-rules", "ledger-invariants", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "card-service/src/main/java/github/lms/lemuel/card/domain/CardAccount.java",
      ),
      ["card-service-rules", "tdd-discipline"],
    );
  });
  test("insurance/deposit sources route to their new *-rules skills (돈 경로 커버리지 공백 해소)", () => {
    assert.deepEqual(
      routeSkills(
        "insurance-service/src/main/java/github/lms/lemuel/insurance/domain/Policy.java",
      ),
      ["insurance-domain-rules", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "deposit-service/src/main/java/github/lms/lemuel/deposit/domain/SellerDepositAccount.java",
      ),
      ["deposit-domain-rules", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "deposit-service/src/main/java/github/lms/lemuel/deposit/adapter/in/kafka/SettlementConfirmedConsumer.java",
      ),
      ["deposit-domain-rules", "idempotency-and-events", "tdd-discipline"],
    );
  });

  test("organization sources route to organization-domain-rules (커버리지 완결 — 16/16)", () => {
    assert.deepEqual(
      routeSkills(
        "organization-service/src/main/java/github/lms/lemuel/organization/domain/Membership.java",
      ),
      ["organization-domain-rules", "tdd-discipline"],
    );
    assert.deepEqual(
      routeSkills(
        "organization-service/src/main/java/github/lms/lemuel/organization/adapter/out/event/OrganizationEventPublisherAdapter.java",
      ),
      ["organization-domain-rules", "idempotency-and-events", "tdd-discipline"],
    );
  });

  test("card outbox publisher keeps the domain rules ahead of the event procedure", () => {
    assert.deepEqual(
      routeSkills(
        "card-service/src/main/java/github/lms/lemuel/card/adapter/out/event/CardEventPublisher.java",
      ),
      ["card-service-rules", "idempotency-and-events", "tdd-discipline"],
    );
  });
  test("kafka consumer path adds idempotency-and-events on top of the service rules", () => {
    assert.deepEqual(
      routeSkills(
        "settlement-service/src/main/java/github/lms/lemuel/settlement/adapter/in/kafka/OrderEventConsumer.java",
      ),
      ["settlement-domain-rules", "idempotency-and-events", "tdd-discipline"],
    );
  });
  test("test sources get the tdd-discipline procedure reminder", () => {
    assert.deepEqual(
      routeSkills(
        "settlement-service/src/test/java/github/lms/lemuel/payout/application/service/PayoutServiceTest.java",
      ),
      ["settlement-domain-rules", "tdd-discipline"],
    );
  });
  test("suggestions are capped at 3 even when more routes match", () => {
    const skills = routeSkills(
      "settlement-service/src/main/java/github/lms/lemuel/settlement/ledger/adapter/in/kafka/LedgerEventConsumer.java",
    );
    assert.equal(skills.length, 3);
    assert.deepEqual(skills, [
      "ledger-invariants",
      "settlement-domain-rules",
      "idempotency-and-events",
    ]);
  });
  test("event contract fixtures route regardless of extension", () => {
    assert.deepEqual(
      routeSkills(
        "shared-common/src/testFixtures/resources/contracts/events/order.created.schema.json",
      ),
      ["event-contract-change"],
    );
  });
  test("hookify capture rule files route to the guard porting workflow", () => {
    assert.deepEqual(routeSkills(".claude/hookify.block-raw-sql.local.md"), [
      "hookify-to-guard",
    ]);
    assert.deepEqual(
      routeSkills("C:\\repo\\.claude\\hookify.warn-rm.local.md"),
      ["hookify-to-guard"],
    );
  });
  test("non-source files stay silent; unmapped-service sources still get tdd-discipline", () => {
    assert.deepEqual(routeSkills("settlement-service/README.md"), []);
    assert.deepEqual(routeSkills("gateway-service/src/main/java/App.java"), [
      "tdd-discipline",
    ]);
    assert.deepEqual(routeSkills(undefined), []);
  });
});

describe("decideHookOutput", () => {
  const writeEvent = (sessionId) => ({
    session_id: sessionId,
    tool_name: "Edit",
    tool_input: {
      file_path:
        "loan-service/src/main/java/github/lms/lemuel/loan/domain/LoanAdvance.java",
    },
  });

  test("suggests once per session, then dedupes; new session suggests again", async () => {
    const repoRoot = await temporaryRepo();
    const first = await decideHookOutput(writeEvent("session-a"), { repoRoot });
    assert.ok(first, "first edit must produce a suggestion");
    const parsed = JSON.parse(first);
    assert.equal(parsed.hookSpecificOutput.hookEventName, "PreToolUse");
    assert.match(
      parsed.hookSpecificOutput.additionalContext,
      /loan-domain-rules/,
    );
    assert.equal(
      await decideHookOutput(writeEvent("session-a"), { repoRoot }),
      null,
    );
    assert.ok(await decideHookOutput(writeEvent("session-b"), { repoRoot }));
    const suggestions = readJsonl(
      join(repoRoot, ...LOG_DIR_SEGMENTS, "skill-suggestions.jsonl"),
    );
    // 세션당 (loan-domain-rules + tdd-discipline) 2건 × 2세션
    assert.equal(suggestions.length, 4);
  });

  test("Skill invocations are logged as usage, not suggestions", async () => {
    const repoRoot = await temporaryRepo();
    const output = await decideHookOutput(
      {
        session_id: "s",
        tool_name: "Skill",
        tool_input: { skill: "settlement-domain-rules" },
      },
      { repoRoot },
    );
    assert.equal(output, null);
    const usage = readJsonl(
      join(repoRoot, ...LOG_DIR_SEGMENTS, "skill-usage.jsonl"),
    );
    assert.equal(usage.length, 1);
    assert.equal(usage[0].skill, "settlement-domain-rules");
  });

  test("malformed events and CLI misuse never block (exit 0)", async () => {
    const repoRoot = await temporaryRepo();
    assert.equal(await decideHookOutput(null, { repoRoot }), null);
    assert.equal(
      await runRouterCli(["--hook"], {
        repoRoot,
        stdin: "not json",
        stderr: () => {},
      }),
      0,
    );
    assert.equal(
      await runRouterCli(["--bogus"], { repoRoot, stderr: () => {} }),
      0,
    );
  });
});

describe("state GC (세션 상태 파일 무한 누적 방지)", () => {
  const stateDir = (repoRoot) => join(repoRoot, ".claude", "harness", "state");
  const ageFile = async (path, days, now) => {
    const stale = new Date(now.getTime() - days * 86_400_000);
    await utimes(path, stale, stale);
  };

  test("보존기간을 넘긴 라우터 상태만 지우고, 신선한 상태·다른 파일은 남긴다", async () => {
    const repoRoot = await temporaryRepo();
    const now = new Date();
    await mkdir(stateDir(repoRoot), { recursive: true });
    const old = join(stateDir(repoRoot), "skill-router-old-session.json");
    const fresh = join(stateDir(repoRoot), "skill-router-fresh.json");
    const other = join(stateDir(repoRoot), "other-tool-state.json");
    for (const f of [old, fresh, other]) await writeFile(f, "{}", "utf8");
    await ageFile(old, 20, now);
    await ageFile(other, 20, now); // 라우터 소유가 아닌 파일은 오래돼도 건드리지 않는다
    assert.equal(await pruneStaleState(repoRoot, { now }), 1);
    assert.equal(existsSync(old), false);
    assert.equal(existsSync(fresh), true);
    assert.equal(existsSync(other), true);
  });

  test("상태 디렉토리가 없으면 조용히 0 을 반환한다", async () => {
    assert.equal(await pruneStaleState(await temporaryRepo()), 0);
  });

  test("훅 경로가 기회적으로 GC 를 수행한다", async () => {
    const repoRoot = await temporaryRepo();
    const now = new Date();
    await mkdir(stateDir(repoRoot), { recursive: true });
    const old = join(stateDir(repoRoot), "skill-router-dead-session.json");
    await writeFile(old, "{}", "utf8");
    await ageFile(old, 20, now);
    await decideHookOutput(
      {
        session_id: "gc-live",
        tool_name: "Edit",
        tool_input: {
          file_path:
            "loan-service/src/main/java/github/lms/lemuel/loan/domain/LoanAdvance.java",
        },
      },
      { repoRoot, now },
    );
    assert.equal(existsSync(old), false);
    assert.equal(
      existsSync(join(stateDir(repoRoot), "skill-router-gc-live.json")),
      true,
    );
  });
});

describe("telemetry", () => {
  test("guardHitRecords carries rule id, file, line and mode", () => {
    const now = new Date("2026-07-18T00:00:00Z");
    const records = guardHitRecords(
      "hook",
      [{ id: "MONEY-PRIMITIVE", file: "a.java", line: 3, msg: "x" }],
      now,
    );
    assert.deepEqual(records, [
      {
        ts: "2026-07-18T00:00:00.000Z",
        mode: "hook",
        id: "MONEY-PRIMITIVE",
        file: "a.java",
        line: 3,
      },
    ]);
  });

  test("HARNESS_TELEMETRY=off disables writes", async () => {
    const repoRoot = await temporaryRepo();
    const written = await appendJsonl(
      repoRoot,
      "guard-hits.jsonl",
      { a: 1 },
      { env: { HARNESS_TELEMETRY: "off" } },
    );
    assert.equal(written, false);
    assert.equal(
      existsSync(join(repoRoot, ...LOG_DIR_SEGMENTS, "guard-hits.jsonl")),
      false,
    );
  });

  test("guard hook mode records blocked violations to guard-hits.jsonl", async () => {
    const repoRoot = await temporaryRepo();
    const file =
      "settlement-service/src/main/java/github/lms/lemuel/settlement/domain/Money.java";
    await mkdir(join(repoRoot, ...file.split("/").slice(0, -1)), {
      recursive: true,
    });
    await writeFile(
      join(repoRoot, ...file.split("/")),
      "placeholder\n",
      "utf8",
    );
    const event = {
      tool_name: "Write",
      tool_input: { file_path: file, content: "double amount = 1.0;\n" },
    };
    const exitCode = await runGuardCli(["--hook"], {
      repoRoot,
      stdin: JSON.stringify(event),
      stdout: () => {},
      stderr: () => {},
    });
    assert.equal(exitCode, 2);
    const hits = readJsonl(
      join(repoRoot, ...LOG_DIR_SEGMENTS, "guard-hits.jsonl"),
    );
    assert.equal(hits.length, 1);
    assert.equal(hits[0].id, "MONEY-PRIMITIVE");
    assert.equal(hits[0].mode, "hook");
  });

  test("summarize surfaces zero-fire rules and ignored suggestions", () => {
    const report = summarize({
      hits: [
        {
          ts: "2026-07-18T01:00:00Z",
          mode: "hook",
          id: "MONEY-PRIMITIVE",
          file: "a.java",
          line: 1,
        },
      ],
      usage: [{ ts: "2026-07-18T01:00:00Z", skill: "money-safety" }],
      suggestions: [
        {
          ts: "2026-07-18T01:00:00Z",
          skill: "ledger-invariants",
          file: "b.java",
        },
      ],
      now: new Date("2026-07-18T02:00:00Z"),
    });
    assert.match(report, /MONEY-PRIMITIVE/);
    assert.match(report, /IMMUTABLE-HISTORY.*0회/);
    assert.match(report, /ledger-invariants/);
  });
});
