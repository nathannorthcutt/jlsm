#!/usr/bin/env node
/**
 * Reconcile construct cards — invert outgoing edges to add incoming edges,
 * derive co_mutators/co_readers, flag inconsistencies.
 *
 * Usage: node reconcile-cards.js <feature-dir>
 *
 * Reads:  <feature-dir>/construct-cards.yaml
 * Writes: <feature-dir>/analysis-cards.yaml (reconciled)
 *
 * This is a mechanical data transformation. No LLM judgment involved.
 */
'use strict';

const fs = require('fs');
const path = require('path');

// ── YAML parsing (restricted subset matching card schema) ──────────

function parseYamlCards(text) {
  const cards = [];
  const documents = ('\n' + text.trim() + '\n').split(/\n---\s*\n/);
  for (const doc of documents) {
    const trimmed = doc.trim();
    if (!trimmed) continue;
    const card = parseYamlBlock(trimmed, 0);
    if (card && card.construct) cards.push(card);
  }
  return cards;
}

function parseYamlBlock(text, baseIndent) {
  const result = {};
  const lines = text.split('\n');
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const stripped = line.trim();

    if (!stripped || stripped.startsWith('#')) { i++; continue; }

    const indent = line.length - line.trimStart().length;
    if (indent < baseIndent) break;

    const match = line.match(/^(\s*)([a-z_]+):\s*(.*)/);
    if (!match) { i++; continue; }

    const key = match[2];
    const value = match[3].trim();

    if (value === '' || value === '[]') {
      let nextIndent = null;
      for (let j = i + 1; j < lines.length; j++) {
        const ns = lines[j].trim();
        if (ns && !ns.startsWith('#')) {
          nextIndent = lines[j].length - lines[j].trimStart().length;
          break;
        }
      }

      if (value === '[]') {
        result[key] = [];
      } else if (nextIndent !== null && nextIndent > indent) {
        const blockLines = [];
        for (let j = i + 1; j < lines.length; j++) {
          const l = lines[j];
          const ls = l.trim();
          if (!ls || ls.startsWith('#')) { blockLines.push(l); continue; }
          const li = l.length - l.trimStart().length;
          if (li <= indent) break;
          blockLines.push(l);
        }

        const blockText = blockLines.join('\n');
        let firstContent = null;
        for (const bl of blockLines) {
          const bs = bl.trim();
          if (bs && !bs.startsWith('#')) { firstContent = bs; break; }
        }

        if (firstContent && firstContent.startsWith('- ')) {
          result[key] = parseYamlList(blockText, nextIndent);
        } else {
          result[key] = parseYamlBlock(blockText, nextIndent);
        }
        i += 1 + blockLines.length;
        continue;
      } else {
        result[key] = '';
      }
    } else if (value.startsWith('[') && value.endsWith(']')) {
      const inner = value.slice(1, -1).trim();
      if (inner) {
        result[key] = inner.split(',').map(x =>
          x.trim().replace(/^["']|["']$/g, ''));
      } else {
        result[key] = [];
      }
    } else {
      result[key] = value.replace(/^["']|["']$/g, '');
    }

    i++;
  }

  return result;
}

function parseYamlList(text, baseIndent) {
  const items = [];
  const lines = text.split('\n');
  let currentItem = null;

  for (const line of lines) {
    const stripped = line.trim();
    if (!stripped || stripped.startsWith('#')) continue;

    if (stripped.startsWith('- ')) {
      if (currentItem !== null) items.push(currentItem);

      const rest = stripped.slice(2).trim();
      if (!rest.includes(':') || rest.startsWith('"') || rest.startsWith("'")) {
        currentItem = rest.replace(/^["']|["']$/g, '');
      } else {
        currentItem = {};
        const colonIdx = rest.indexOf(':');
        const k = rest.slice(0, colonIdx).trim();
        const v = rest.slice(colonIdx + 1).trim().replace(/^["']|["']$/g, '');
        currentItem[k] = v;
      }
    } else if (currentItem !== null && typeof currentItem === 'object' &&
               !Array.isArray(currentItem)) {
      if (stripped.includes(':')) {
        const colonIdx = stripped.indexOf(':');
        const k = stripped.slice(0, colonIdx).trim();
        const v = stripped.slice(colonIdx + 1).trim().replace(/^["']|["']$/g, '');
        currentItem[k] = v;
      }
    }
  }
  if (currentItem !== null) items.push(currentItem);
  return items;
}

// ── Reconciliation ─────────────────────────────────────────────────

function reconcile(cards) {
  const byName = {};
  for (const c of cards) byName[c.construct] = c;

  // Initialize reconciliation fields
  for (const card of cards) {
    let exec = card.execution || {};
    if (typeof exec === 'string') exec = {};
    card.execution = exec;
    if (!exec.invoked_by) exec.invoked_by = [];

    let state = card.state || {};
    if (typeof state === 'string') state = {};
    card.state = state;
    if (!state.read_by) state.read_by = [];
    if (!state.written_by) state.written_by = [];
    if (!state.co_mutators) state.co_mutators = [];
    if (!state.co_readers) state.co_readers = [];

    if (!card.reconciliation) card.reconciliation = { inconsistencies: [] };
  }

  // Pass 1: Invert execution edges
  for (const card of cards) {
    const source = card.construct;
    let invokes = (card.execution || {}).invokes || [];
    if (typeof invokes === 'string') invokes = invokes ? [invokes] : [];

    for (const targetName of invokes) {
      if (targetName in byName) {
        const invokedBy = byName[targetName].execution.invoked_by;
        if (!invokedBy.includes(source)) invokedBy.push(source);
      }
    }
  }

  // Pass 2: Invert state edges
  for (const card of cards) {
    const source = card.construct;

    let readsExt = (card.state || {}).reads_external || [];
    if (typeof readsExt === 'string') readsExt = readsExt ? [readsExt] : [];

    for (const ref of readsExt) {
      const parts = typeof ref === 'string' ? ref.split('.') : [];
      if (parts.length >= 1) {
        const targetName = parts[0];
        if (targetName in byName) {
          const readBy = byName[targetName].state.read_by;
          if (!readBy.includes(source)) readBy.push(source);
        }
      }
    }

    let writesExt = (card.state || {}).writes_external || [];
    if (typeof writesExt === 'string') writesExt = writesExt ? [writesExt] : [];

    for (const ref of writesExt) {
      const parts = typeof ref === 'string' ? ref.split('.') : [];
      if (parts.length >= 1) {
        const targetName = parts[0];
        if (targetName in byName) {
          const writtenBy = byName[targetName].state.written_by;
          if (!writtenBy.includes(source)) writtenBy.push(source);
        }
      }
    }
  }

  // Pass 3: Derive co_mutators
  for (const card of cards) {
    const owner = card.construct;
    const owns = (card.state || {}).owns || [];
    if (!owns || (Array.isArray(owns) && owns.length === 0)) continue;

    const writers = new Set();
    for (const otherCard of cards) {
      if (otherCard.construct === owner) continue;
      let wExt = (otherCard.state || {}).writes_external || [];
      if (typeof wExt === 'string') wExt = wExt ? [wExt] : [];
      for (const ref of wExt) {
        if (typeof ref === 'string' && ref.split('.')[0] === owner) {
          writers.add(otherCard.construct);
        }
      }
    }

    for (const writer of writers) {
      const otherWriters = new Set([...writers].filter(w => w !== writer));
      if (otherWriters.size > 0) {
        const writerCard = byName[writer];
        for (const ow of otherWriters) {
          if (!writerCard.state.co_mutators.includes(ow)) {
            writerCard.state.co_mutators.push(ow);
          }
        }
      }
      if (!card.state.co_mutators.includes(writer)) {
        card.state.co_mutators.push(writer);
      }
    }
  }

  // Pass 4: Derive co_readers
  for (const card of cards) {
    const owner = card.construct;
    const owns = (card.state || {}).owns || [];
    if (!owns || (Array.isArray(owns) && owns.length === 0)) continue;

    const readers = new Set();
    for (const otherCard of cards) {
      if (otherCard.construct === owner) continue;
      let rExt = (otherCard.state || {}).reads_external || [];
      if (typeof rExt === 'string') rExt = rExt ? [rExt] : [];
      for (const ref of rExt) {
        if (typeof ref === 'string' && ref.split('.')[0] === owner) {
          readers.add(otherCard.construct);
        }
      }
    }

    for (const reader of readers) {
      const otherReaders = [...readers].filter(r => r !== reader);
      for (const ordr of otherReaders) {
        const readerCard = byName[reader];
        if (!readerCard.state.co_readers.includes(ordr)) {
          readerCard.state.co_readers.push(ordr);
        }
      }
    }
  }

  // Pass 5: Flag inconsistencies
  for (const card of cards) {
    const source = card.construct;
    let invokes = (card.execution || {}).invokes || [];
    if (typeof invokes === 'string') invokes = invokes ? [invokes] : [];

    for (const targetName of invokes) {
      if (targetName in byName) {
        const targetCard = byName[targetName];
        let entryPoints = (targetCard.execution || {}).entry_points || [];
        if (typeof entryPoints === 'string') {
          entryPoints = entryPoints ? [entryPoints] : [];
        }

        if (entryPoints.length > 0 &&
            !entryPoints.some(ep =>
              targetName.includes(ep) || targetName.endsWith('.' + ep))) {
          card.reconciliation.inconsistencies.push({
            type: 'invokes_without_entry_point',
            source: source,
            target: targetName,
            detail: source + ' invokes ' + targetName + ' but ' +
                    targetName + ' entry_points [' + entryPoints.join(', ') +
                    '] may not include the called method'
          });
        }
      }
    }
  }

  return cards;
}

// ── YAML emission ──────────────────────────────────────────────────

function asStrList(val) {
  if (typeof val === 'string') return val ? [val] : [];
  if (Array.isArray(val)) return val.map(String);
  return [];
}

function emitYamlCard(card) {
  const lines = [];

  lines.push('construct: ' + (card.construct || ''));
  lines.push('kind: ' + (card.kind || ''));
  lines.push('location: ' + (card.location || ''));
  lines.push('');

  // Execution
  lines.push('execution:');
  let execS = card.execution || {};
  if (typeof execS === 'string') execS = {};
  lines.push('  invokes: [' + asStrList(execS.invokes).join(', ') + ']');
  lines.push('  invoked_by: [' + asStrList(execS.invoked_by).join(', ') + ']');
  lines.push('  entry_points: [' + asStrList(execS.entry_points).join(', ') + ']');
  lines.push('');

  // State
  lines.push('state:');
  let stateS = card.state || {};
  if (typeof stateS === 'string') stateS = {};
  lines.push('  owns: [' + asStrList(stateS.owns).join(', ') + ']');
  lines.push('  reads_external: [' + asStrList(stateS.reads_external).join(', ') + ']');
  lines.push('  writes_external: [' + asStrList(stateS.writes_external).join(', ') + ']');
  lines.push('  read_by: [' + asStrList(stateS.read_by).join(', ') + ']');
  lines.push('  written_by: [' + asStrList(stateS.written_by).join(', ') + ']');
  lines.push('  co_mutators: [' + asStrList(stateS.co_mutators).join(', ') + ']');
  lines.push('  co_readers: [' + asStrList(stateS.co_readers).join(', ') + ']');
  lines.push('');

  // Contracts
  lines.push('contracts:');
  let contracts = card.contracts || {};
  if (typeof contracts === 'string') contracts = {};

  const guarantees = contracts.guarantees || [];
  if (!guarantees.length) {
    lines.push('  guarantees: []');
  } else {
    lines.push('  guarantees:');
    for (const g of guarantees) {
      if (typeof g === 'object' && g !== null) {
        lines.push('    - what: ' + (g.what || ''));
        lines.push('      evidence: ' + (g.evidence || ''));
      } else {
        lines.push('    - ' + g);
      }
    }
  }

  const assumptions = contracts.assumptions || [];
  if (!assumptions.length) {
    lines.push('  assumptions: []');
  } else {
    lines.push('  assumptions:');
    for (const a of assumptions) {
      if (typeof a === 'object' && a !== null) {
        lines.push('    - what: ' + (a.what || ''));
        lines.push('      evidence: ' + (a.evidence || ''));
        lines.push('      failure_mode: ' + (a.failure_mode || ''));
      } else {
        lines.push('    - ' + a);
      }
    }
  }
  lines.push('');

  // Reconciliation
  let recon = card.reconciliation || {};
  if (typeof recon === 'string') recon = {};
  const inconsistencies = recon.inconsistencies || [];
  lines.push('reconciliation:');
  if (!inconsistencies.length) {
    lines.push('  inconsistencies: []');
  } else {
    lines.push('  inconsistencies:');
    for (const inc of inconsistencies) {
      if (typeof inc === 'object' && inc !== null) {
        lines.push('    - type: ' + (inc.type || ''));
        lines.push('      source: ' + (inc.source || ''));
        lines.push('      target: ' + (inc.target || ''));
        lines.push('      detail: ' + (inc.detail || ''));
      }
    }
  }

  return lines.join('\n');
}

// ── Main ───────────────────────────────────────────────────────────

function main() {
  if (process.argv.length < 3) {
    process.stderr.write('Usage: node reconcile-cards.js <feature-dir>\n');
    process.exit(1);
  }

  const featureDir = process.argv[2];
  const inputPath = path.join(featureDir, 'construct-cards.yaml');
  const outputPath = path.join(featureDir, 'analysis-cards.yaml');
  const tmpPath = outputPath + '.tmp';

  if (!fs.existsSync(inputPath)) {
    process.stderr.write('Error: ' + inputPath + ' not found\n');
    process.exit(1);
  }

  let text;
  try {
    text = fs.readFileSync(inputPath, 'utf8');
  } catch (e) {
    process.stderr.write('Error reading ' + inputPath + ': ' + e.message + '\n');
    process.exit(0);
  }

  let cards;
  try {
    cards = parseYamlCards(text);
  } catch (e) {
    process.stderr.write('Error parsing ' + inputPath + ': ' + e.message + '\n');
    process.exit(0);
  }

  if (!cards.length) {
    process.stderr.write('Error: no cards parsed from input\n');
    process.exit(1);
  }

  const reconciled = reconcile(cards);

  let totalInconsistencies = 0;
  let coMutatorCount = 0;
  for (const c of reconciled) {
    const recon = c.reconciliation || {};
    totalInconsistencies += (recon.inconsistencies || []).length;
    const st = c.state || {};
    if ((st.co_mutators || []).length > 0) coMutatorCount++;
  }

  // Atomic write
  try {
    const output = reconciled.map(emitYamlCard).join('\n---\n') + '\n';
    fs.writeFileSync(tmpPath, output, 'utf8');
    fs.renameSync(tmpPath, outputPath);
  } catch (e) {
    process.stderr.write('Error writing ' + outputPath + ': ' + e.message + '\n');
    process.exit(0);
  }

  console.log('Reconciled ' + reconciled.length + ' cards — ' +
              totalInconsistencies + ' inconsistencies, ' +
              coMutatorCount + ' with co_mutators');
}

main();
