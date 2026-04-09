#!/usr/bin/env node
'use strict';
/**
 * vallorcine status line — Node.js enhanced implementation.
 *
 * Reads pipeline state from JSON state files and produces ANSI-colored status
 * output identical to statusline.sh / statusline.py.
 *
 * Stdlib only — no npm dependencies.
 */

const fs = require('fs');
const path = require('path');

function fmtTokens(n) {
  if (n >= 1_000_000) return `${Math.floor(n / 1_000_000)}.${Math.floor((n % 1_000_000) / 100_000)}M`;
  if (n >= 1_000) return `${Math.floor(n / 1_000)}.${Math.floor((n % 1_000) / 100)}K`;
  return String(n);
}

function readJsonOrShell(filePath) {
  try {
    const text = fs.readFileSync(filePath, 'utf8').trim();
    if (!text) return {};
    if (text.startsWith('{')) return JSON.parse(text);
    // Legacy shell variable format
    const result = {};
    for (const line of text.split('\n')) {
      const trimmed = line.trim();
      if (trimmed.startsWith('#') || !trimmed.includes('=')) continue;
      const idx = trimmed.indexOf('=');
      const key = trimmed.slice(0, idx).trim();
      let val = trimmed.slice(idx + 1).replace(/^['"]|['"]$/g, '');
      if (val.startsWith("$'")) val = val.slice(2).replace(/'$/, '');
      result[key] = val;
    }
    return result;
  } catch { return {}; }
}

function readStageFromStatus(statusPath) {
  let stage = '', substage = '';
  try {
    const lines = fs.readFileSync(statusPath, 'utf8').split('\n');
    for (const line of lines) {
      if (line.startsWith('**Stage:**')) stage = line.split('**Stage:**')[1].trim();
      else if (line.startsWith('**Substage:**')) { substage = line.split('**Substage:**')[1].trim(); break; }
    }
  } catch {}
  return [stage, substage];
}

function substageLabel(stage, substage) {
  const labels = {
    scoping: { interviewing: 'interviewing', 'confirming-brief': 'confirming brief', complete: 'complete' },
    planning: { 'loading-context': 'loading context', 'surveying-codebase': 'surveying code', 'confirmed-design': 'design confirmed', 'writing-stubs': 'writing stubs', 'contract-revised': 'contract revised' },
    testing: { planning: 'planning tests', 'confirming-plan': 'confirming plan', 'writing-tests': 'writing tests', 'verifying-failures': 'verifying failures' },
    implementation: { 'loading-context': 'loading context', implementing: 'implementing' },
    refactor: { 'loading-context': 'loading context' },
  };
  const map = labels[stage] || {};
  if (map[substage]) return map[substage];

  if (stage === 'testing') {
    if (substage.includes('verified') && substage.includes('failing')) return 'tests verified';
    if (substage.startsWith('escalation')) return 'escalation';
  } else if (stage === 'implementation') {
    if (substage.startsWith('implemented:')) return substage.split(':')[1].trim();
    if (substage.includes('all') && substage.includes('tests') && substage.includes('passing')) return 'all passing';
    if (substage.startsWith('escalat')) return 'escalation';
  } else if (stage === 'refactor') {
    if (substage.includes('complete')) return 'complete';
    const rmap = { coding: 'coding standards', duplication: 'DRY', security: 'security', performance: 'performance', missing: 'missing tests', integration: 'integration', documentation: 'docs', 'security-review': 'security review', 'final-lint': 'final lint' };
    for (const [k, v] of Object.entries(rmap)) { if (substage.includes(k)) return v; }
    if (substage.startsWith('escalat')) return 'escalation';
    if (substage.startsWith('cycle-5')) return 'cycle limit';
  } else if (stage === 'pr') {
    if (substage === 'pr-draft-written') return 'draft ready';
  }
  return '';
}

function buildStageDisplay(slug, stage, substage) {
  const displayStage = { implementation: 'implementing', pr: 'PR draft' }[stage] || stage;
  const sub = stage === 'domains' ? substage : substageLabel(stage, substage);
  const base = `${slug} · ${displayStage}`;
  return sub ? `${base} · ${sub}` : base;
}

function nowUtc() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function main() {
  let input = '';
  try { input = fs.readFileSync(0, 'utf8'); } catch {}

  let session = {};
  try { session = JSON.parse(input); } catch {}

  const ctxWindow = session.context_window || {};
  const contextPct = ctxWindow.used_percentage;
  const ctxSize = ctxWindow.context_window_size;

  let currentCtxTokens = null;
  if (contextPct != null && ctxSize != null) {
    currentCtxTokens = Math.floor(contextPct * ctxSize / 100);
  }

  let stageDisplay = '';
  let stageTokens = '';
  const baselineFile = '.claude/.statusline-baseline';

  const tokenState = readJsonOrShell('.claude/.token-state');
  const featureDir = tokenState.feature_dir || '';
  const cachedStage = tokenState.cached_stage || '';

  if (featureDir && fs.existsSync(`${featureDir}/status.md`)) {
    const slug = path.basename(featureDir);
    const [actualStage, substage] = readStageFromStatus(`${featureDir}/status.md`);
    const currentStage = actualStage || cachedStage;
    const isTerminal = ['pr/created', 'pr/complete'].includes(`${currentStage}/${substage}`);

    if (!isTerminal) {
      stageDisplay = buildStageDisplay(slug, currentStage, substage);

      if (currentCtxTokens != null) {
        const baseline = readJsonOrShell(baselineFile);
        const baselineStage = baseline.baseline_stage || '';
        let baselineCtx = parseInt(baseline.baseline_ctx_tokens) || 0;

        if (baselineStage !== currentStage || baselineCtx === 0) {
          try {
            const newBaseline = { baseline_stage: currentStage, baseline_ctx_tokens: currentCtxTokens, baseline_timestamp: nowUtc() };
            fs.mkdirSync(path.dirname(baselineFile), { recursive: true });
            const tmpFile = baselineFile + '.tmp';
            fs.writeFileSync(tmpFile, JSON.stringify(newBaseline) + '\n');
            fs.renameSync(tmpFile, baselineFile);
            baselineCtx = currentCtxTokens;
          } catch {}
        }

        const stageUsed = Math.max(0, currentCtxTokens - baselineCtx);
        stageTokens = fmtTokens(stageUsed);
      }
    } else {
      try { fs.unlinkSync(baselineFile); } catch {}
    }
  } else if (!Object.keys(tokenState).length) {
    try { fs.unlinkSync(baselineFile); } catch {}
  }

  // Read subagent state
  let subagentDisplay = '';
  const subagentFile = '.claude/.subagent-state';
  try {
    const subagent = JSON.parse(fs.readFileSync(subagentFile, 'utf8'));
    if (subagent.description) subagentDisplay = `agent: ${subagent.description}`;
  } catch {}

  // Build output
  const parts = [];
  if (stageDisplay) parts.push(`\\033[36m${stageDisplay}\\033[0m`);
  if (subagentDisplay) parts.push(`\\033[35m${subagentDisplay}\\033[0m`);
  if (stageTokens) parts.push(`${stageTokens} tokens`);
  if (contextPct != null) {
    const ctxInt = Math.floor(contextPct);
    const color = ctxInt >= 80 ? '31' : ctxInt >= 50 ? '33' : '32';
    parts.push(`\\033[${color}mctx ${ctxInt}%\\033[0m`);
  }

  if (parts.length) console.log(parts.join(' · '));
}

try { main(); } catch {}
