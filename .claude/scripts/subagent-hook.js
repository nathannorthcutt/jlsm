#!/usr/bin/env node
'use strict';
/**
 * vallorcine SubagentStart/SubagentStop hook — Node.js implementation.
 *
 * Writes/clears .claude/.subagent-state when subagents start/stop.
 *
 * Stdlib only — no npm dependencies.
 */

const fs = require('fs');

function nowUtc() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function main() {
  let input = '';
  try { input = fs.readFileSync(0, 'utf8'); } catch {}

  const stateFile = '.claude/.subagent-state';

  let data = {};
  try { data = JSON.parse(input); } catch {
    // If we can't parse input, clean up stale state as a safe default
    try { fs.unlinkSync(stateFile); } catch {}
    return;
  }

  const event = data.hook_event_name || '';

  if (event === 'SubagentStart') {
    try {
      fs.mkdirSync('.claude', { recursive: true });
      const state = {
        active: true,
        agent_id: data.agent_id || '',
        description: data.description || '',
        started: nowUtc(),
      };
      const tmpFile = stateFile + '.tmp';
      fs.writeFileSync(tmpFile, JSON.stringify(state) + '\n');
      fs.renameSync(tmpFile, stateFile);
    } catch {}
  } else if (event === 'SubagentStop') {
    try { fs.unlinkSync(stateFile); } catch {}
  }
}

try { main(); } catch {}
