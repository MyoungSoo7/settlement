#!/usr/bin/env node

import { existsSync, readFileSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

export const INTERVIEW_HARNESS_STATE = {
  seedGateRequiredFields: ['goal', 'constraints', 'acceptance_criteria'],
  stageOrder: ['evolve-step', 'ontology', 'compare'],
  threshold: 0.85,
  maxCycles: 5,
  firstCycleComparison: 'skip',
  stopReasons: ['convergence', 'safety_valve'],
};

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function normalizePath(value) {
  return String(value).split(sep).join('/');
}

function splitLines(text) {
  return String(text).replace(/\r\n/g, '\n').split('\n');
}

function extractSection(text, heading) {
  const lines = splitLines(text);
  const start = lines.findIndex((line) => new RegExp(`^##\\s+${escapeRegExp(heading)}\\s*$`).test(line));
  if (start === -1) return '';
  const section = [];
  for (let index = start + 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (/^##\s+/.test(line)) break;
    section.push(line);
  }
  return section.join('\n');
}

function extractFence(section, fenceName = 'yaml') {
  const pattern = new RegExp(`^\\\`\\\`\\\`${escapeRegExp(fenceName)}\\s*\\r?\\n([\\s\\S]*?)\\r?\\n\\\`\\\`\\\``, 'm');
  const match = String(section).match(pattern);
  return match ? match[1] : '';
}

function parseScalar(value) {
  const trimmed = value.trim();
  if (!trimmed) return '';
  if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function parseListBlock(section, key) {
  const lines = splitLines(section);
  const index = lines.findIndex((line) => new RegExp(`^\\s*${escapeRegExp(key)}\\s*:\\s*$`).test(line));
  if (index === -1) return [];
  const values = [];
  for (let cursor = index + 1; cursor < lines.length; cursor += 1) {
    const line = lines[cursor];
    if (/^\s*-\s+/.test(line)) {
      values.push(parseScalar(line.replace(/^\s*-\s+/, '')));
      continue;
    }
    if (/^\s*$/.test(line)) continue;
    if (/^\S/.test(line)) break;
    if (!/^\s+/.test(line)) break;
  }
  return values;
}

function parseInlineList(value) {
  const trimmed = value.trim();
  if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) return null;
  const body = trimmed.slice(1, -1).trim();
  if (!body) return [];
  return body.split(',').map((entry) => parseScalar(entry));
}

function parseValue(section, key) {
  const lines = splitLines(section);
  for (const line of lines) {
    const match = line.match(new RegExp(`^\\s*${escapeRegExp(key)}\\s*:\\s*(.*)$`));
    if (match) {
      const inlineList = parseInlineList(match[1]);
      return inlineList ?? parseScalar(match[1]);
    }
  }
  return '';
}

export function parseHarnessEntity(text, heading) {
  const section = extractSection(text, heading);
  if (!section) return null;
  const yaml = extractFence(section, 'yaml') || extractFence(section, 'yml') || section;
  return {
    raw: section,
    yaml,
    goal: parseValue(yaml, 'goal'),
    idea: parseValue(yaml, 'idea'),
    boundary: parseValue(yaml, 'boundary'),
    constraints: parseListBlock(yaml, 'constraints'),
    acceptanceCriteria: parseListBlock(yaml, 'acceptance_criteria'),
    properties: parseListBlock(yaml, 'properties'),
  };
}

export function parseInterviewHarnessDoc(text) {
  const seed = parseHarnessEntity(text, 'Seed');
  const ontology = parseHarnessEntity(text, 'Ontology');
  return {
    seed,
    ontology,
    seedComplete: Boolean(seed?.goal && seed.constraints.length > 0 && seed.acceptanceCriteria.length > 0),
    ontologyComplete: Boolean(ontology?.idea && ontology.boundary && ontology.properties.length > 0),
  };
}

export function compareOntologies(previous, current, { boundaryConfirmed = false } = {}) {
  if (!previous || !current) {
    throw new Error('compareOntologies requires previous and current ontology values');
  }
  const idea = normalizePath(previous.idea) === normalizePath(current.idea) ? 1 : 0;
  const boundary = boundaryConfirmed ? 1 : 0;
  const previousProperties = new Set(previous.properties.map((value) => normalizePath(value)));
  const currentProperties = new Set(current.properties.map((value) => normalizePath(value)));
  const intersection = [...previousProperties].filter((value) => currentProperties.has(value)).length;
  const union = new Set([...previousProperties, ...currentProperties]).size;
  const properties = union === 0 ? 1 : intersection / union;
  return {
    idea,
    boundary,
    properties,
    similarity: (idea + boundary + properties) / 3,
  };
}

export function deriveInterviewHarnessTransition({
  phase = 'seed-gate',
  cycle = 0,
  seedComplete = false,
  currentOntology = null,
  previousOntology = null,
  boundaryConfirmed = false,
  threshold = INTERVIEW_HARNESS_STATE.threshold,
  maxCycles = INTERVIEW_HARNESS_STATE.maxCycles,
  firstCycleComparison = INTERVIEW_HARNESS_STATE.firstCycleComparison,
} = {}) {
  if (phase === 'seed-gate') {
    if (!seedComplete) {
      return {
        phase: 'seed-gate',
        cycle: 0,
        nextStep: 'socrates',
        stopReason: null,
        comparison: null,
      };
    }
    return {
      phase: 'cycle',
      cycle: 1,
      nextStep: 'evolve-step',
      stopReason: null,
      comparison: null,
    };
  }

  if (phase === 'cycle-start') {
    return {
      phase: 'cycle',
      cycle: cycle || 1,
      nextStep: 'evolve-step',
      stopReason: null,
      comparison: null,
    };
  }

  if (phase === 'evolve-step') {
    return {
      phase: 'ontology',
      cycle: cycle || 1,
      nextStep: 'ontology',
      stopReason: null,
      comparison: null,
    };
  }

  if (phase === 'ontology') {
    if (cycle === 1 && firstCycleComparison === 'skip') {
      return {
        phase: 'cycle',
        cycle: 2,
        nextStep: 'evolve-step',
        stopReason: null,
        comparison: {
          skipped: true,
          reason: 'first-cycle-skip',
        },
      };
    }
    return {
      phase: 'compare',
      cycle: cycle || 1,
      nextStep: 'compare',
      stopReason: null,
      comparison: null,
    };
  }

  if (phase === 'compare') {
    const similarity = compareOntologies(previousOntology, currentOntology, { boundaryConfirmed });
    if (similarity.similarity >= threshold) {
      return {
        phase: 'stop',
        cycle,
        nextStep: 'stop',
        stopReason: 'convergence',
        comparison: similarity,
      };
    }
    if (cycle >= maxCycles) {
      return {
        phase: 'stop',
        cycle,
        nextStep: 'stop',
        stopReason: 'safety_valve',
        comparison: similarity,
      };
    }
    return {
      phase: 'cycle',
      cycle: cycle + 1,
      nextStep: 'evolve-step',
      stopReason: null,
      comparison: similarity,
    };
  }

  if (phase === 'stop') {
    return {
      phase: 'stop',
      cycle,
      nextStep: 'stop',
      stopReason: null,
      comparison: null,
    };
  }

  throw new Error(`unsupported interview-harness phase: ${phase}`);
}

export function loadInterviewHarnessInputs(root) {
  const repoRoot = resolve(root);
  const docs = ['.claude/scratch/socrates.md', '.symposium/scratch/socrates.md']
    .map((path) => resolve(repoRoot, ...path.split('/')))
    .find((path) => existsSync(path));
  if (!docs) {
    throw new Error('could not locate socrates scratch file');
  }
  const scratch = ['.claude/scratch/interview-harness.md', '.symposium/scratch/interview-harness.md']
    .map((path) => resolve(repoRoot, ...path.split('/')))
    .find((path) => existsSync(path));
  const doc = parseInterviewHarnessDoc(readFileSync(docs, 'utf8'));
  const scratchDoc = scratch ? parseInterviewHarnessDoc(readFileSync(scratch, 'utf8')) : { seedComplete: false, ontologyComplete: false };
  return {
    root: repoRoot,
    docsPath: docs,
    scratchPath: scratch,
    seedComplete: doc.seedComplete,
    currentOntology: doc.ontology,
    previousOntology: scratchDoc.ontologyComplete ? scratchDoc.ontology : null,
  };
}

export function deriveInterviewHarnessState({ root, state = {}, boundaryConfirmed = false } = {}) {
  const inputs = loadInterviewHarnessInputs(root);
  const phase = state.phase ?? 'seed-gate';
  const cycle = state.cycle ?? 0;
  return deriveInterviewHarnessTransition({
    phase,
    cycle,
    seedComplete: inputs.seedComplete,
    currentOntology: inputs.currentOntology,
    previousOntology: state.previousOntology ?? inputs.previousOntology,
    boundaryConfirmed,
  });
}

export async function runInterviewHarnessCli(args, io = {}) {
  const stdout = io.stdout ?? ((text) => process.stdout.write(text));
  const stderr = io.stderr ?? ((text) => process.stderr.write(text));
  const value = (flag, fallback) => {
    const index = args.indexOf(flag);
    return index === -1 ? fallback : args[index + 1];
  };
  try {
    for (let index = 0; index < args.length; index += 2) {
      if (!['--root', '--boundary-confirmed', '--phase', '--cycle'].includes(args[index])) {
        throw new Error(`unsupported argument: ${args[index]}`);
      }
      if (!args[index + 1] || args[index + 1].startsWith('--')) {
        throw new Error(`missing value for argument: ${args[index]}`);
      }
    }
    const root = resolve(value('--root', process.cwd()));
    const boundaryConfirmed = value('--boundary-confirmed', 'false') === 'true';
    const phase = value('--phase', 'seed-gate');
    const cycle = Number(value('--cycle', '0'));
    const result = deriveInterviewHarnessState({
      root,
      boundaryConfirmed,
      state: { phase, cycle: Number.isFinite(cycle) ? cycle : 0 },
    });
    stdout(`${JSON.stringify(result, null, 2)}\n`);
    return 0;
  } catch (error) {
    stderr(`interview-harness: ${error.message}\n`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runInterviewHarnessCli(process.argv.slice(2));
}
