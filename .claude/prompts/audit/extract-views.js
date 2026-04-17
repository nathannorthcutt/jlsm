#!/usr/bin/env node
/**
 * Extract per-lens card views from reconciled analysis cards.
 *
 * Usage:
 *   Phase 1 (detect active lenses):
 *     node extract-views.js detect <feature-dir>
 *
 *   Phase 2 (produce projections after pruning):
 *     node extract-views.js project <feature-dir>
 *
 * Phase 1 reads analysis-cards.yaml, evaluates lens predicates,
 * writes active-lenses.md with candidate-active lenses.
 *
 * Phase 2 reads analysis-cards.yaml and active-lenses.md (after LLM
 * pruning has marked lenses as confirmed/pruned), produces per-lens
 * projected card files and the full analysis view.
 */
'use strict';

const fs = require('fs');
const path = require('path');

// ── YAML parsing (same restricted subset as reconcile-cards.js) ────

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

// ── Helper functions ───────────────────────────────────────────────

function getList(card, section, field) {
  const s = card[section];
  if (!s || typeof s === 'string') return [];
  const val = s[field];
  if (val === undefined || val === null) return [];
  if (typeof val === 'string') return val ? [val] : [];
  return Array.isArray(val) ? val : [];
}

function getLocationFile(card) {
  const loc = card.location || '';
  if (loc.includes(':')) return loc.split(':')[0];
  return loc;
}

function constructsCrossModule(cardA, cardB) {
  return getLocationFile(cardA) !== getLocationFile(cardB);
}

// ── Lens predicate functions ───────────────────────────────────────

function checkSharedState(card, byName) {
  for (const field of ['owns', 'reads_external', 'writes_external',
                        'co_mutators', 'co_readers']) {
    if (getList(card, 'state', field).length > 0) return true;
  }
  return false;
}

function checkResourceLifecycle(card, byName) {
  const owns = getList(card, 'state', 'owns');
  if (owns.length === 0) return false;
  const targets = getList(card, 'execution', 'invokes')
    .concat(getList(card, 'execution', 'invoked_by'));
  for (const target of targets) {
    if (target in byName) {
      if (getList(byName[target], 'state', 'owns').length > 0) return true;
    }
  }
  return false;
}

function checkContractBoundaries(card, byName) {
  for (const target of getList(card, 'execution', 'invokes')) {
    if (target in byName && constructsCrossModule(card, byName[target])) {
      return true;
    }
  }
  for (const target of getList(card, 'execution', 'invoked_by')) {
    if (target in byName && constructsCrossModule(card, byName[target])) {
      return true;
    }
  }
  return false;
}

function checkDataTransformation(card, byName) {
  const reads = getList(card, 'state', 'reads_external');
  const writes = getList(card, 'state', 'writes_external');
  if (reads.length > 0 && writes.length > 0) {
    const readTargets = new Set();
    for (const r of reads) {
      if (r.includes('.')) readTargets.add(r.split('.')[0]);
    }
    const writeTargets = new Set();
    for (const w of writes) {
      if (w.includes('.')) writeTargets.add(w.split('.')[0]);
    }
    // Check sets are not equal
    if (readTargets.size !== writeTargets.size) return true;
    for (const rt of readTargets) {
      if (!writeTargets.has(rt)) return true;
    }
  }
  return false;
}

function checkConcurrency(card, byName) {
  return getList(card, 'state', 'co_mutators').length > 0;
}

function checkDispatchRouting(card, byName) {
  const invokes = getList(card, 'execution', 'invokes');
  if (invokes.length < 3) return false;
  const targetsInScope = invokes.filter(t => t in byName);
  if (targetsInScope.length < 3) return false;

  for (let i = 0; i < targetsInScope.length; i++) {
    const t1 = targetsInScope[i];
    const t1Invokes = new Set(getList(byName[t1], 'execution', 'invokes'));
    const t1InvokedBy = new Set(getList(byName[t1], 'execution', 'invoked_by'));
    for (let j = i + 1; j < targetsInScope.length; j++) {
      const t2 = targetsInScope[j];
      if (t1Invokes.has(t2) || t1InvokedBy.has(t2)) return false;
    }
  }
  return true;
}

// ── Lens definitions ───────────────────────────────────────────────

const LENSES = {
  shared_state: {
    description: 'Shared mutable state consistency',
    challenge: "This codebase has shared mutable state between constructs. Prove it doesn't.",
    fields: {
      execution: ['invokes', 'invoked_by'],
      state: ['owns', 'reads_external', 'writes_external',
              'read_by', 'written_by', 'co_mutators', 'co_readers'],
    },
    predicate: checkSharedState,
  },
  resource_lifecycle: {
    description: 'Resource acquisition/release lifecycle',
    challenge: "This codebase has resource lifecycle patterns (acquire/release, open/close). Prove it doesn't.",
    fields: {
      execution: ['invokes', 'invoked_by', 'entry_points'],
      state: ['owns'],
    },
    predicate: checkResourceLifecycle,
  },
  contract_boundaries: {
    description: 'Cross-module contract violations',
    challenge: "This codebase has cross-module invocations where caller and callee assumptions could mismatch. Prove it doesn't.",
    fields: {
      execution: ['invokes', 'invoked_by', 'entry_points'],
      reconciliation: ['inconsistencies'],
    },
    predicate: checkContractBoundaries,
  },
  data_transformation: {
    description: 'Data format/type transformation fidelity',
    challenge: "This codebase has data transformation chains where data changes form between producer and consumer. Prove it doesn't.",
    fields: {
      execution: ['invokes', 'invoked_by'],
      state: ['reads_external', 'writes_external'],
    },
    predicate: checkDataTransformation,
  },
  concurrency: {
    description: 'Concurrent access to shared state',
    challenge: "This codebase has concurrent access patterns — multiple constructs mutate the same state. Prove it doesn't.",
    fields: {
      execution: ['invoked_by'],
      state: ['owns', 'co_mutators', 'co_readers',
              'writes_external', 'written_by'],
    },
    predicate: checkConcurrency,
  },
  dispatch_routing: {
    description: 'Dispatch/routing pattern correctness',
    challenge: "This codebase has dispatch patterns where one construct routes to multiple independent handlers. Prove it doesn't.",
    fields: {
      execution: ['invokes', 'invoked_by', 'entry_points'],
    },
    predicate: checkDispatchRouting,
  },
};

// ── Phase 1: Detect active lenses ──────────────────────────────────

function detectLenses(cards) {
  const byName = {};
  for (const c of cards) byName[c.construct] = c;
  const results = {};
  for (const [lensName, lensDef] of Object.entries(LENSES)) {
    const qualifying = [];
    for (const card of cards) {
      if (lensDef.predicate(card, byName)) qualifying.push(card.construct);
    }
    if (qualifying.length > 0) results[lensName] = qualifying;
  }
  return results;
}

function writeActiveLenses(featureDir, lensResults) {
  const lines = [
    '# Active Domain Lenses',
    '',
    'Candidate-active lenses detected from construct cards.',
    'Each lens must survive pruning: the LLM must prove the domain',
    'does NOT apply to eliminate it. Lenses not listed here had zero',
    'qualifying constructs and are already pruned.',
    '',
    '---',
    '',
  ];

  const sortedNames = Object.keys(lensResults).sort();
  for (const lensName of sortedNames) {
    const qualifying = lensResults[lensName];
    const lensDef = LENSES[lensName];
    lines.push('## ' + lensName);
    lines.push('');
    lines.push('**Description:** ' + lensDef.description);
    lines.push('**Qualifying constructs:** ' + qualifying.length);
    lines.push('**Challenge:** "' + lensDef.challenge + '"');
    lines.push('**Status:** CANDIDATE');
    lines.push('');
    lines.push('Constructs: ' + qualifying.slice(0, 10).join(', '));
    if (qualifying.length > 10) {
      lines.push('  ... and ' + (qualifying.length - 10) + ' more');
    }
    lines.push('');
    lines.push('---');
    lines.push('');
  }

  const allLenses = new Set(Object.keys(LENSES));
  const activeLenses = new Set(Object.keys(lensResults));
  const eliminated = [...allLenses].filter(l => !activeLenses.has(l)).sort();

  if (eliminated.length > 0) {
    lines.push('## Eliminated (zero qualifying constructs)');
    lines.push('');
    for (const lensName of eliminated) {
      lines.push('- **' + lensName + '**: ' + LENSES[lensName].description +
                  ' — no constructs matched predicate');
    }
    lines.push('');
  }

  const filePath = path.join(featureDir, 'active-lenses.md');
  const tmpPath = filePath + '.tmp';
  try {
    fs.writeFileSync(tmpPath, lines.join('\n'), 'utf8');
    fs.renameSync(tmpPath, filePath);
  } catch (e) {
    process.stderr.write('Error writing ' + filePath + ': ' + e.message + '\n');
  }

  return [Object.keys(lensResults).length, eliminated.length];
}

// ── Phase 2: Produce projections ───────────────────────────────────

function parseActiveLenses(featureDir) {
  const filePath = path.join(featureDir, 'active-lenses.md');
  if (!fs.existsSync(filePath)) {
    process.stderr.write('Error: ' + filePath + ' not found\n');
    process.exit(1);
  }

  let text;
  try {
    text = fs.readFileSync(filePath, 'utf8');
  } catch (e) {
    process.stderr.write('Error reading ' + filePath + ': ' + e.message + '\n');
    return [];
  }

  const confirmed = [];
  let currentLens = null;

  for (const line of text.split('\n')) {
    const match = line.trim().match(/^## ([a-z_]+)\s*$/);
    if (match) {
      currentLens = match[1];
      continue;
    }
    if (currentLens && line.includes('**Status:**')) {
      if (line.includes('CONFIRMED')) {
        confirmed.push(currentLens);
      }
      currentLens = null;
    }
  }
  return confirmed;
}

function projectCard(card, lensName) {
  const lensDef = LENSES[lensName];
  const fieldMap = lensDef.fields;

  const projected = {
    construct: card.construct,
    kind: card.kind || '',
    location: card.location || '',
  };

  for (const [section, fields] of Object.entries(fieldMap)) {
    const sectionData = card[section];
    if (!sectionData || typeof sectionData === 'string') continue;
    const projectedSection = {};
    for (const field of fields) {
      if (field in sectionData) {
        projectedSection[field] = sectionData[field];
      }
    }
    if (Object.keys(projectedSection).length > 0) {
      projected[section] = projectedSection;
    }
  }

  return projected;
}

function emitProjectedCard(card) {
  const lines = [];
  lines.push('construct: ' + (card.construct || ''));
  lines.push('kind: ' + (card.kind || ''));
  lines.push('location: ' + (card.location || ''));

  for (const section of ['execution', 'state', 'reconciliation']) {
    if (section in card) {
      lines.push('');
      lines.push(section + ':');
      const sectionData = card[section];
      for (const [key, val] of Object.entries(sectionData)) {
        if (Array.isArray(val)) {
          if (val.length === 0) {
            lines.push('  ' + key + ': []');
          } else if (typeof val[0] === 'object' && val[0] !== null) {
            lines.push('  ' + key + ':');
            for (const item of val) {
              let first = true;
              for (const [k, v] of Object.entries(item)) {
                const prefix = first ? '    - ' : '      ';
                lines.push(prefix + k + ': ' + v);
                first = false;
              }
            }
          } else {
            lines.push('  ' + key + ': [' + val.join(', ') + ']');
          }
        } else {
          lines.push('  ' + key + ': ' + val);
        }
      }
    }
  }

  return lines.join('\n');
}

function produceProjections(featureDir, cards, confirmedLenses) {
  const byName = {};
  for (const c of cards) byName[c.construct] = c;
  const stats = {};

  for (const lensName of confirmedLenses) {
    if (!(lensName in LENSES)) {
      process.stderr.write("Warning: unknown lens '" + lensName + "', skipping\n");
      continue;
    }
    const lensDef = LENSES[lensName];
    const qualifying = cards.filter(c => lensDef.predicate(c, byName));
    const projected = qualifying.map(c => projectCard(c, lensName));

    const output = projected.map(emitProjectedCard).join('\n---\n') + '\n';
    const filePath = path.join(featureDir, 'lens-' + lensName + '-cards.yaml');
    const tmpPath = filePath + '.tmp';
    try {
      fs.writeFileSync(tmpPath, output, 'utf8');
      fs.renameSync(tmpPath, filePath);
    } catch (e) {
      process.stderr.write('Error writing ' + filePath + ': ' + e.message + '\n');
      continue;
    }
    stats[lensName] = projected.length;
  }

  return stats;
}

// ── Main ───────────────────────────────────────────────────────────

function main() {
  if (process.argv.length < 4) {
    process.stderr.write('Usage: node extract-views.js <detect|project> <feature-dir>\n');
    process.exit(1);
  }

  const phase = process.argv[2];
  const featureDir = process.argv[3];
  const cardsPath = path.join(featureDir, 'analysis-cards.yaml');

  if (!fs.existsSync(cardsPath)) {
    process.stderr.write('Error: ' + cardsPath + ' not found\n');
    process.exit(1);
  }

  let text;
  try {
    text = fs.readFileSync(cardsPath, 'utf8');
  } catch (e) {
    process.stderr.write('Error reading ' + cardsPath + ': ' + e.message + '\n');
    process.exit(0);
  }

  let cards;
  try {
    cards = parseYamlCards(text);
  } catch (e) {
    process.stderr.write('Error parsing ' + cardsPath + ': ' + e.message + '\n');
    process.exit(0);
  }

  if (!cards.length) {
    process.stderr.write('Error: no cards parsed\n');
    process.exit(1);
  }

  if (phase === 'detect') {
    const lensResults = detectLenses(cards);
    const [active, eliminated] = writeActiveLenses(featureDir, lensResults);
    console.log('Detected ' + active + ' candidate lenses, ' +
                eliminated + ' eliminated — active: ' +
                Object.keys(lensResults).sort().join(', '));
  } else if (phase === 'project') {
    const confirmed = parseActiveLenses(featureDir);
    if (confirmed.length === 0) {
      process.stderr.write('No confirmed lenses found in active-lenses.md\n');
      process.exit(1);
    }
    const stats = produceProjections(featureDir, cards, confirmed);
    const total = Object.values(stats).reduce((a, b) => a + b, 0);
    const details = Object.entries(stats).sort()
      .map(([k, v]) => k + '=' + v).join(', ');
    console.log('Projected ' + confirmed.length + ' lenses, ' +
                total + ' total card projections — ' + details);
  } else {
    process.stderr.write("Unknown phase: " + phase + ". Use 'detect' or 'project'.\n");
    process.exit(1);
  }
}

main();
