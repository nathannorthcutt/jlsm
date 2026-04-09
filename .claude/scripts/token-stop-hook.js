#!/usr/bin/env node
'use strict';
/**
 * vallorcine token tracking Stop hook — Node.js implementation.
 *
 * Fires on every Claude response. Detects pipeline stage transitions and logs
 * token usage. No jq dependency — uses stdlib JSON for all parsing.
 *
 * Stdlib only — no npm dependencies.
 */

const fs = require('fs');
const path = require('path');
const os = require('os');

function nowUtc() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

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

function writeState(filePath, data) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const tmpFile = filePath + '.tmp';
  fs.writeFileSync(tmpFile, JSON.stringify(data) + '\n');
  fs.renameSync(tmpFile, filePath);
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

function findTranscript() {
  const projectDir = process.cwd();
  const projectId = projectDir.replace(/^\//, '').replace(/\//g, '-');
  const sessionDir = path.join(os.homedir(), '.claude', 'projects', `-${projectId}`);
  try {
    let newest = '';
    let newestMtime = -1;
    for (const f of fs.readdirSync(sessionDir)) {
      if (!f.endsWith('.jsonl')) continue;
      const mtime = fs.statSync(path.join(sessionDir, f)).mtimeMs;
      if (mtime > newestMtime) {
        newestMtime = mtime;
        newest = f;
      }
    }
    return newest ? path.join(sessionDir, newest) : '';
  } catch { return ''; }
}

function sumUsage(transcript, startLine) {
  const result = { input: 0, output: 0, cache_create: 0, cache_read: 0, messages: 0, totalLines: 0 };
  try {
    const lines = fs.readFileSync(transcript, 'utf8').split('\n');
    // Exclude trailing empty line from split
    result.totalLines = lines[lines.length - 1] === '' ? lines.length - 1 : lines.length;
    for (let i = startLine - 1; i < lines.length; i++) {
      if (!lines[i].trim()) continue;
      let entry;
      try { entry = JSON.parse(lines[i]); } catch { continue; }
      if (entry.type !== 'assistant') continue;
      const msg = entry.message || {};
      if (msg.stop_reason == null) continue;
      const usage = msg.usage || {};
      if ((usage.input_tokens || 0) <= 0) continue;
      result.input += usage.input_tokens || 0;
      result.output += usage.output_tokens || 0;
      result.cache_create += usage.cache_creation_input_tokens || 0;
      result.cache_read += usage.cache_read_input_tokens || 0;
      result.messages += 1;
    }
  } catch {}
  return result;
}

function updateStatusMdActualTokens(statusPath, stageCap, actualStr) {
  try {
    const lines = fs.readFileSync(statusPath, 'utf8').split('\n');
    const updated = lines.map(line => {
      if (!line.includes('|')) return line;
      const parts = line.split('|');
      if (parts.length >= 7 && parts[1].trim() === stageCap) {
        parts[5] = ` ${actualStr} `;
        return parts.join('|');
      }
      return line;
    });
    const tmpFile = statusPath + '.tmp';
    fs.writeFileSync(tmpFile, updated.join('\n'));
    fs.renameSync(tmpFile, statusPath);
  } catch {}
}

function main() {
  // Consume stdin
  try { fs.readFileSync(0, 'utf8'); } catch {}

  const stateFile = '.claude/.token-state';

  if (!fs.existsSync(stateFile) && !fs.existsSync('.feature')) return;

  // Tracking active
  if (fs.existsSync(stateFile)) {
    const state = readJsonOrShell(stateFile);
    if (!Object.keys(state).length) { try { fs.unlinkSync(stateFile); } catch {} return; }

    const featureDir = state.feature_dir || '';
    const cachedStage = state.cached_stage || '';
    let transcript = state.transcript || '';
    let startLine = parseInt(state.start_line, 10) || 1;
    const timestamp = state.timestamp || '';

    const statusPath = `${featureDir}/status.md`;
    if (!featureDir || !fs.existsSync(statusPath)) { try { fs.unlinkSync(stateFile); } catch {} return; }

    const [currentStage, substage] = readStageFromStatus(statusPath);
    const isTerminal = ['pr/created', 'pr/complete'].includes(`${currentStage}/${substage}`);

    if (!currentStage || currentStage === cachedStage) {
      if (isTerminal) try { fs.unlinkSync(stateFile); } catch {}
      return;
    }

    // Stage transition
    const currentTranscript = findTranscript();
    if (currentTranscript !== transcript || !fs.existsSync(transcript)) {
      transcript = currentTranscript;
      startLine = 1;
    }

    if (transcript && fs.existsSync(transcript)) {
      const usage = sumUsage(transcript, startLine);

      // Append to token log
      const logFile = `${featureDir}/token-log.md`;
      if (!fs.existsSync(logFile)) {
        fs.writeFileSync(logFile,
          '# Token Usage Log\n\n' +
          '| Phase | Messages | Input | Output | Cache Create | Cache Read | Started | Ended |\n' +
          '|-------|----------|-------|--------|--------------|------------|---------|-------|\n'
        );
      }
      fs.appendFileSync(logFile,
        `| ${cachedStage} | ${usage.messages} | ${usage.input} | ${usage.output} ` +
        `| ${usage.cache_create} | ${usage.cache_read} ` +
        `| ${timestamp || 'unknown'} | ${nowUtc()} |\n`
      );

      // Update status.md
      const actualStr = `${fmtTokens(usage.input)} in / ${fmtTokens(usage.output)} out`;
      const stageCap = cachedStage.charAt(0).toUpperCase() + cachedStage.slice(1);
      updateStatusMdActualTokens(statusPath, stageCap, actualStr);

      if (isTerminal) { try { fs.unlinkSync(stateFile); } catch {} return; }

      // Update state (line count from sumUsage, no re-read)
      writeState(stateFile, {
        feature_dir: featureDir,
        cached_stage: currentStage,
        transcript,
        start_line: usage.totalLines + 1,
        timestamp: nowUtc(),
      });
    }
    return;
  }

  // Cold start
  try {
    const features = fs.readdirSync('.feature');
    for (const slug of features) {
      const statusFile = `.feature/${slug}/status.md`;
      if (!fs.existsSync(statusFile)) continue;
      const [stage, substage] = readStageFromStatus(statusFile);
      if (!stage) continue;
      if (['pr/complete', 'pr/created'].includes(`${stage}/${substage}`)) continue;

      const transcript = findTranscript();
      if (!transcript) return;
      let currentLine = 1;
      try {
        const buf = fs.readFileSync(transcript);
        let count = 0;
        for (let i = 0; i < buf.length; i++) {
          if (buf[i] === 0x0a) count++;
        }
        currentLine = count;
      } catch {}

      writeState(stateFile, {
        feature_dir: `.feature/${slug}`,
        cached_stage: stage,
        transcript,
        start_line: currentLine + 1,
        timestamp: nowUtc(),
      });
      return;
    }
  } catch {}
}

try { main(); } catch {}
