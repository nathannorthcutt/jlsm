#!/usr/bin/env node
/**
 * vallorcine KB search — BM25-based ranked search over .kb/ entries.
 *
 * Two-phase ranking:
 *   Phase 1: Coarse ranking over category CLAUDE.md files (Tags, description,
 *            Contents table Subject/Best For columns).
 *   Phase 2: Enriched ranking over subject file frontmatter (title, aliases,
 *            tags) with field weights.
 *
 * Usage:
 *   node kb-search.js "<query>" [<kb_root>] [<top_n>]
 *
 * Output (stdout): ranked list, one per line:
 *   <score>  <topic>/<category>/<subject>
 *
 * Exit 0 always. Empty output if KB doesn't exist or no matches.
 *
 * Stdlib only — no npm dependencies.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// ── Tokenization ────────────────────────────────────────────────────────────

const TOKEN_RE = /[A-Z][a-zA-Z0-9]+|[a-z_][a-z_0-9]+/g;

const STOPWORDS = new Set([
    'must', 'should', 'shall', 'will', 'when', 'then', 'that', 'this',
    'with', 'from', 'into', 'each', 'have', 'does', 'been', 'also',
    'only', 'the', 'and', 'for', 'are', 'but', 'not', 'you', 'all',
    'can', 'her', 'was', 'one', 'our', 'out',
]);

function tokenize(text) {
    const tokens = [];
    let match;
    TOKEN_RE.lastIndex = 0;
    while ((match = TOKEN_RE.exec(text)) !== null) {
        const tok = match[0].toLowerCase();
        if (tok.length >= 4 && !STOPWORDS.has(tok)) {
            tokens.push(tok);
        }
    }
    return tokens;
}

// ── BM25 ────────────────────────────────────────────────────────────────────

const K1 = 1.2;
const B = 0.75;

function bm25Score(queryTokens, documents) {
    if (!documents.length || !queryTokens.length) return [];

    const n = documents.length;
    let totalLen = 0;
    for (const d of documents) totalLen += d.tokens.length;
    const avgdl = totalLen / n || 1;

    // Document frequency per query token
    const uniqueQuery = [...new Set(queryTokens)];
    const df = {};
    for (const qt of uniqueQuery) {
        let count = 0;
        for (const d of documents) {
            if (d.tokenSet.has(qt)) count++;
        }
        df[qt] = count;
    }

    // Score each document
    for (const doc of documents) {
        let score = 0;
        const dl = doc.tokens.length;

        // Term frequency map
        const tfMap = {};
        for (const t of doc.tokens) {
            tfMap[t] = (tfMap[t] || 0) + 1;
        }

        for (const qt of uniqueQuery) {
            if (!df[qt]) continue;
            const tf = tfMap[qt] || 0;
            if (tf === 0) continue;

            // IDF
            const idf = Math.log((n - df[qt] + 0.5) / (df[qt] + 0.5) + 1.0);
            // TF with length normalization
            const tfNorm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgdl));

            score += idf * tfNorm;
        }

        doc.score = score;
    }

    documents.sort((a, b) => b.score - a.score);
    return documents;
}

// ── Category CLAUDE.md parsing ──────────────────────────────────────────────

function parseCategoryIndex(filePath) {
    const result = { tags: '', description: '', subjects: [] };

    let text;
    try {
        text = fs.readFileSync(filePath, 'utf8');
    } catch {
        return result;
    }

    const lines = text.split('\n');
    let i = 0;
    const total = lines.length;

    // Find Tags line
    while (i < total) {
        const line = lines[i].trim();
        if (line.startsWith('*Tags:') && line.endsWith('*')) {
            result.tags = line.slice(6, -1).trim();
            i++;
            break;
        }
        i++;
    }

    // Description paragraph
    const descLines = [];
    while (i < total) {
        const line = lines[i].trim();
        if (line.startsWith('## ')) break;
        if (line) descLines.push(line);
        i++;
    }
    result.description = descLines.join(' ');

    // Contents table
    let inContents = false;
    let headerSeen = false;
    while (i < total) {
        const line = lines[i].trim();
        if (line === '## Contents') {
            inContents = true;
            i++;
            continue;
        }
        if (inContents) {
            if (line.startsWith('## ')) break;
            if (line.startsWith('|')) {
                if (!headerSeen) {
                    if (line.includes('---') || line.includes('File') || line.includes('Subject')) {
                        headerSeen = line.startsWith('|') && line.includes('---');
                        i++;
                        continue;
                    }
                    headerSeen = true;
                }
                // Parse row
                const cells = line.split('|').map(c => c.trim()).filter(c => c);
                if (cells.length >= 2) {
                    const fileCell = cells[0];
                    const subjectName = cells.length > 1 ? cells[1] : '';
                    const bestFor = cells.length > 4 ? cells[4] : '';

                    // Extract filename from [file.md](file.md)
                    const linkMatch = fileCell.match(/\[([^\]]+)\]/);
                    const filename = linkMatch ? linkMatch[1] : fileCell;

                    result.subjects.push({
                        file: filename,
                        name: subjectName,
                        bestFor: bestFor,
                    });
                }
            }
        }
        i++;
    }

    return result;
}

// ── Subject file frontmatter parsing ────────────────────────────────────────

function parseSubjectFrontmatter(filePath) {
    const result = { title: '', aliases: [], tags: [], summary: '' };

    let text;
    try {
        text = fs.readFileSync(filePath, 'utf8');
    } catch {
        return result;
    }

    const lines = text.split('\n');
    if (!lines.length) return result;

    // Find frontmatter block
    let fmStart = -1;
    let fmEnd = -1;
    for (let i = 0; i < lines.length; i++) {
        if (lines[i].trim() === '---') {
            if (fmStart < 0) {
                fmStart = i;
            } else {
                fmEnd = i;
                break;
            }
        }
    }

    if (fmStart >= 0 && fmEnd > fmStart) {
        const fmLines = lines.slice(fmStart + 1, fmEnd);

        // First pass: inline values
        for (const line of fmLines) {
            const stripped = line.trim();

            if (stripped.startsWith('title:')) {
                result.title = stripped.slice(6).trim().replace(/^["']|["']$/g, '');
            } else if (stripped.startsWith('aliases:')) {
                const val = stripped.slice(8).trim();
                if (val.startsWith('[')) {
                    result.aliases = parseInlineList(val);
                }
            } else if (stripped.startsWith('tags:')) {
                const val = stripped.slice(5).trim();
                if (val.startsWith('[')) {
                    result.tags = parseInlineList(val);
                }
            }
        }

        // Second pass: multi-line aliases/tags
        let inField = null;
        for (const line of fmLines) {
            const stripped = line.trim();
            if (stripped.startsWith('aliases:') && !stripped.slice(8).trim().startsWith('[')) {
                inField = 'aliases';
                continue;
            } else if (stripped.startsWith('tags:') && !stripped.slice(5).trim().startsWith('[')) {
                inField = 'tags';
                continue;
            } else if (!stripped.startsWith('- ') && !stripped.startsWith('#')) {
                if (stripped.includes(':')) inField = null;
                continue;
            }

            if (inField && stripped.startsWith('- ')) {
                const val = stripped.slice(2).trim().replace(/^["']|["']$/g, '');
                result[inField].push(val);
            }
        }
    }

    // Extract ## summary paragraph
    let inSummary = false;
    const summaryLines = [];
    const bodyStart = fmEnd >= 0 ? fmEnd + 1 : 0;
    for (let i = bodyStart; i < lines.length; i++) {
        const stripped = lines[i].trim();
        if (stripped.toLowerCase() === '## summary') {
            inSummary = true;
            continue;
        }
        if (inSummary) {
            if (stripped.startsWith('## ') || (!stripped && summaryLines.length)) {
                break;
            }
            if (stripped && !stripped.startsWith('<!--')) {
                summaryLines.push(stripped);
            }
        }
    }
    result.summary = summaryLines.join(' ');

    return result;
}

function parseInlineList(val) {
    val = val.replace(/^\[|\]$/g, '');
    return val.split(',')
        .map(item => item.trim().replace(/^["']|["']$/g, ''))
        .filter(item => item);
}

// ── Two-phase search ────────────────────────────────────────────────────────

function discoverCategories(kbRoot) {
    const categories = [];

    let topicDirs;
    try {
        topicDirs = fs.readdirSync(kbRoot, { withFileTypes: true });
    } catch {
        return categories;
    }

    for (const topicEntry of topicDirs.sort((a, b) => a.name.localeCompare(b.name))) {
        if (!topicEntry.isDirectory() || topicEntry.name.startsWith('_') || topicEntry.name.startsWith('.')) {
            continue;
        }
        const topic = topicEntry.name;
        const topicPath = path.join(kbRoot, topic);

        let catDirs;
        try {
            catDirs = fs.readdirSync(topicPath, { withFileTypes: true });
        } catch {
            continue;
        }

        for (const catEntry of catDirs.sort((a, b) => a.name.localeCompare(b.name))) {
            if (!catEntry.isDirectory() || catEntry.name.startsWith('_') || catEntry.name.startsWith('.')) {
                continue;
            }
            const category = catEntry.name;
            const catPath = path.join(topicPath, category);
            const indexPath = path.join(catPath, 'CLAUDE.md');

            try {
                fs.accessSync(indexPath, fs.constants.R_OK);
            } catch {
                continue;
            }

            const parsed = parseCategoryIndex(indexPath);
            categories.push({
                topic,
                category,
                path: catPath,
                parsed,
            });
        }
    }

    return categories;
}

function phase1CoarseRank(queryTokens, categories, limit) {
    const documents = [];
    for (const cat of categories) {
        const p = cat.parsed;

        const textParts = [p.tags, p.description];
        for (const subj of p.subjects) {
            textParts.push(subj.name);
            textParts.push(subj.bestFor);
        }
        const combined = textParts.join(' ');
        const tokens = tokenize(combined);

        documents.push({
            id: `${cat.topic}/${cat.category}`,
            tokens,
            tokenSet: new Set(tokens),
            cat,
        });
    }

    const scored = bm25Score(queryTokens, documents);
    return scored.filter(d => d.score > 0).slice(0, limit);
}

function phase2EnrichedRank(queryTokens, phase1Results, topN) {
    const documents = [];

    for (const result of phase1Results) {
        const cat = result.cat;
        const catPath = cat.path;

        for (const subjInfo of cat.parsed.subjects) {
            let filename = subjInfo.file;
            if (!filename.endsWith('.md')) filename += '.md';
            const subjPath = path.join(catPath, filename);

            try {
                fs.accessSync(subjPath, fs.constants.R_OK);
            } catch {
                continue;
            }

            const fm = parseSubjectFrontmatter(subjPath);
            const subjectStem = filename.endsWith('.md') ? filename.slice(0, -3) : filename;

            // Build weighted token list
            const tokens = [];

            // Title (3x)
            const titleTokens = tokenize(fm.title);
            for (let r = 0; r < 3; r++) tokens.push(...titleTokens);

            // Aliases (3x)
            for (const alias of fm.aliases) {
                const aliasTokens = tokenize(alias);
                for (let r = 0; r < 3; r++) tokens.push(...aliasTokens);
            }

            // Tags (2x)
            for (const tag of fm.tags) {
                const tagTokens = tokenize(tag);
                for (let r = 0; r < 2; r++) tokens.push(...tagTokens);
            }

            // Summary (1.5x ≈ 1x + half)
            const summaryTokens = tokenize(fm.summary);
            tokens.push(...summaryTokens);
            tokens.push(...summaryTokens.slice(0, Math.floor(summaryTokens.length / 2)));

            // Category-level context (1x)
            tokens.push(...tokenize(subjInfo.name));
            tokens.push(...tokenize(subjInfo.bestFor));

            const docId = `${cat.topic}/${cat.category}/${subjectStem}`;
            documents.push({
                id: docId,
                tokens,
                tokenSet: new Set(tokens),
            });
        }
    }

    const scored = bm25Score(queryTokens, documents);
    const results = [];
    for (const d of scored) {
        if (d.score > 0) {
            results.push([Math.round(d.score * 100) / 100, d.id]);
        }
    }
    return results.slice(0, topN);
}

// ── Main ────────────────────────────────────────────────────────────────────

function main() {
    if (process.argv.length < 3) process.exit(0);

    const query = process.argv[2];
    const kbRoot = process.argv.length > 3 ? process.argv[3] : '.kb';
    const topN = process.argv.length > 4 ? parseInt(process.argv[4], 10) : 10;

    try {
        fs.accessSync(kbRoot, fs.constants.R_OK);
    } catch {
        process.exit(0);
    }

    const queryTokens = tokenize(query);
    if (!queryTokens.length) process.exit(0);

    const categories = discoverCategories(kbRoot);
    if (!categories.length) process.exit(0);

    // Phase 1: coarse ranking
    const phase1Limit = Math.min(topN * 3, 30);
    const phase1Results = phase1CoarseRank(queryTokens, categories, phase1Limit);

    if (!phase1Results.length) process.exit(0);

    // Phase 2: enriched ranking
    const results = phase2EnrichedRank(queryTokens, phase1Results, topN);

    for (const [score, docId] of results) {
        console.log(`${score.toFixed(2)}  ${docId}`);
    }
}

try {
    main();
} catch {
    process.exit(0);
}
