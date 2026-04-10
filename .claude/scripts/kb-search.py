#!/usr/bin/env python3
"""vallorcine KB search — BM25-based ranked search over .kb/ entries.

Two-phase ranking:
  Phase 1: Coarse ranking over category CLAUDE.md files (Tags, description,
           Contents table Subject/Best For columns).
  Phase 2: Enriched ranking over subject file frontmatter (title, aliases,
           tags) with field weights.

Usage:
  python3 kb-search.py "<query>" [<kb_root>] [<top_n>]

Output (stdout): ranked list, one per line:
  <score>  <topic>/<category>/<subject>

Exit 0 always. Empty output if KB doesn't exist or no matches.

Stdlib only — no pip dependencies.
"""

import math
import os
import re
import sys
from pathlib import Path


# ── Tokenization ─────────────────────────────────────────────────────────────

# Reuses spec-resolve.sh pattern: CamelCase and snake_case tokens
TOKEN_RE = re.compile(r'[A-Z][a-zA-Z0-9]+|[a-z_][a-z_0-9]+')

STOPWORDS = frozenset({
    'must', 'should', 'shall', 'will', 'when', 'then', 'that', 'this',
    'with', 'from', 'into', 'each', 'have', 'does', 'been', 'also',
    'only', 'the', 'and', 'for', 'are', 'but', 'not', 'you', 'all',
    'can', 'her', 'was', 'one', 'our', 'out',
})


def tokenize(text: str) -> list[str]:
    """Extract meaningful tokens from text, lowercased, filtered."""
    tokens = []
    for match in TOKEN_RE.finditer(text):
        tok = match.group().lower()
        if len(tok) >= 4 and tok not in STOPWORDS:
            tokens.append(tok)
    return tokens


# ── BM25 ─────────────────────────────────────────────────────────────────────

K1 = 1.2
B = 0.75


def bm25_score(query_tokens: list[str], documents: list[dict]) -> list[dict]:
    """Score documents against query using Okapi BM25.

    Each document is {"id": str, "tokens": list[str], ...}.
    Returns documents with "score" field added, sorted descending.
    """
    if not documents or not query_tokens:
        return []

    n = len(documents)

    # Average document length
    total_len = sum(len(d['tokens']) for d in documents)
    avgdl = total_len / n if n > 0 else 1

    # Document frequency: how many documents contain each query token
    df = {}
    for qt in set(query_tokens):
        count = 0
        for d in documents:
            if qt in d['token_set']:
                count += 1
        df[qt] = count

    # Score each document
    for doc in documents:
        score = 0.0
        dl = len(doc['tokens'])

        # Term frequency map for this document
        tf_map = {}
        for t in doc['tokens']:
            tf_map[t] = tf_map.get(t, 0) + 1

        for qt in set(query_tokens):
            if qt not in df or df[qt] == 0:
                continue

            tf = tf_map.get(qt, 0)
            if tf == 0:
                continue

            # IDF component: log((N - df + 0.5) / (df + 0.5) + 1)
            idf = math.log((n - df[qt] + 0.5) / (df[qt] + 0.5) + 1.0)

            # TF component with length normalization
            tf_norm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / avgdl))

            score += idf * tf_norm

        doc['score'] = score

    return sorted(documents, key=lambda d: d['score'], reverse=True)


# ── Category CLAUDE.md parsing ───────────────────────────────────────────────

def parse_category_index(path: Path) -> dict:
    """Extract searchable text from a category CLAUDE.md file.

    Returns {"tags": str, "description": str, "subjects": list[dict]}.
    Each subject dict has {"file": str, "name": str, "best_for": str}.
    """
    result = {"tags": "", "description": "", "subjects": []}

    try:
        lines = path.read_text(encoding='utf-8').splitlines()
    except (OSError, UnicodeDecodeError):
        return result

    i = 0
    total = len(lines)

    # Find Tags line: *Tags: ...*
    while i < total:
        line = lines[i].strip()
        if line.startswith('*Tags:') and line.endswith('*'):
            result["tags"] = line[6:-1].strip()
            i += 1
            break
        i += 1

    # Description: paragraph between Tags and ## Contents
    desc_lines = []
    while i < total:
        line = lines[i].strip()
        if line.startswith('## '):
            break
        if line:
            desc_lines.append(line)
        i += 1
    result["description"] = ' '.join(desc_lines)

    # Contents table: find the table rows after ## Contents
    in_contents = False
    header_seen = False
    while i < total:
        line = lines[i].strip()
        if line == '## Contents':
            in_contents = True
            i += 1
            continue
        if in_contents:
            if line.startswith('## '):
                break
            if line.startswith('|'):
                if not header_seen:
                    # Skip header row and separator
                    if '---' in line or 'File' in line or 'Subject' in line:
                        header_seen = line.startswith('|') and '---' in line
                        i += 1
                        continue
                    header_seen = True
                # Parse table row: | file | subject | status | metric | best_for |
                cells = [c.strip() for c in line.split('|')]
                # cells[0] is empty (before first |), cells[-1] may be empty
                cells = [c for c in cells if c]
                if len(cells) >= 2:
                    file_cell = cells[0]
                    subject_name = cells[1] if len(cells) > 1 else ""
                    best_for = cells[4] if len(cells) > 4 else ""

                    # Extract filename from markdown link [file.md](file.md)
                    link_match = re.search(r'\[([^\]]+)\]', file_cell)
                    filename = link_match.group(1) if link_match else file_cell

                    result["subjects"].append({
                        "file": filename,
                        "name": subject_name,
                        "best_for": best_for,
                    })
        i += 1

    return result


# ── Subject file frontmatter parsing ─────────────────────────────────────────

def parse_subject_frontmatter(path: Path) -> dict:
    """Extract title, aliases, tags, and summary from a subject file.

    Parses frontmatter (between --- delimiters) without a YAML library.
    Also extracts the first paragraph after ## summary.
    """
    result = {"title": "", "aliases": [], "tags": [], "summary": ""}

    try:
        text = path.read_text(encoding='utf-8')
    except (OSError, UnicodeDecodeError):
        return result

    lines = text.splitlines()
    if not lines:
        return result

    # Find frontmatter block
    fm_start = -1
    fm_end = -1
    for i, line in enumerate(lines):
        if line.strip() == '---':
            if fm_start < 0:
                fm_start = i
            else:
                fm_end = i
                break

    if fm_start >= 0 and fm_end > fm_start:
        fm_lines = lines[fm_start + 1:fm_end]
        for line in fm_lines:
            stripped = line.strip()

            # title: "..." or title: ...
            if stripped.startswith('title:'):
                val = stripped[6:].strip().strip('"').strip("'")
                result["title"] = val

            # aliases: ["a", "b"] (inline) or aliases: followed by - items
            elif stripped.startswith('aliases:'):
                val = stripped[8:].strip()
                if val.startswith('['):
                    result["aliases"] = _parse_inline_list(val)

            elif stripped.startswith('- ') and result.get("_in_aliases"):
                result["aliases"].append(stripped[2:].strip().strip('"').strip("'"))

            # tags: ["a", "b"]
            elif stripped.startswith('tags:'):
                val = stripped[5:].strip()
                if val.startswith('['):
                    result["tags"] = _parse_inline_list(val)

            elif stripped.startswith('- ') and result.get("_in_tags"):
                result["tags"].append(stripped[2:].strip().strip('"').strip("'"))

        # Second pass for multi-line aliases/tags
        in_field = None
        for line in fm_lines:
            stripped = line.strip()
            if stripped.startswith('aliases:') and not stripped[8:].strip().startswith('['):
                in_field = 'aliases'
                continue
            elif stripped.startswith('tags:') and not stripped[5:].strip().startswith('['):
                in_field = 'tags'
                continue
            elif not stripped.startswith('- ') and not stripped.startswith('#'):
                if ':' in stripped:
                    in_field = None
                continue

            if in_field and stripped.startswith('- '):
                val = stripped[2:].strip().strip('"').strip("'")
                result[in_field].append(val)

    # Remove internal tracking keys
    result.pop("_in_aliases", None)
    result.pop("_in_tags", None)

    # Extract ## summary paragraph
    in_summary = False
    summary_lines = []
    for line in lines[fm_end + 1 if fm_end >= 0 else 0:]:
        stripped = line.strip()
        if stripped.lower() == '## summary':
            in_summary = True
            continue
        if in_summary:
            if stripped.startswith('## ') or (not stripped and summary_lines):
                break
            if stripped and not stripped.startswith('<!--'):
                summary_lines.append(stripped)
    result["summary"] = ' '.join(summary_lines)

    return result


def _parse_inline_list(val: str) -> list[str]:
    """Parse ["a", "b", "c"] from a YAML inline list string."""
    val = val.strip('[]')
    items = []
    for item in val.split(','):
        item = item.strip().strip('"').strip("'")
        if item:
            items.append(item)
    return items


# ── Two-phase search ─────────────────────────────────────────────────────────

def discover_categories(kb_root: Path) -> list[dict]:
    """Find all category CLAUDE.md files and parse them."""
    categories = []

    if not kb_root.is_dir():
        return categories

    for topic_dir in sorted(kb_root.iterdir()):
        if not topic_dir.is_dir() or topic_dir.name.startswith(('_', '.')):
            continue
        topic = topic_dir.name
        for cat_dir in sorted(topic_dir.iterdir()):
            if not cat_dir.is_dir() or cat_dir.name.startswith(('_', '.')):
                continue
            category = cat_dir.name
            index_path = cat_dir / 'CLAUDE.md'
            if index_path.is_file():
                parsed = parse_category_index(index_path)
                categories.append({
                    "topic": topic,
                    "category": category,
                    "path": cat_dir,
                    "parsed": parsed,
                })

    return categories


def phase1_coarse_rank(query_tokens: list[str], categories: list[dict],
                       limit: int) -> list[dict]:
    """Phase 1: BM25 over category index text."""
    documents = []
    for cat in categories:
        p = cat["parsed"]

        # Build document text from tags + description + subject names + best_for
        text_parts = [p["tags"], p["description"]]
        for subj in p["subjects"]:
            text_parts.append(subj["name"])
            text_parts.append(subj["best_for"])
        combined = ' '.join(text_parts)

        tokens = tokenize(combined)
        documents.append({
            "id": f"{cat['topic']}/{cat['category']}",
            "tokens": tokens,
            "token_set": set(tokens),
            "cat": cat,
        })

    scored = bm25_score(query_tokens, documents)
    # Return those with score > 0, up to limit
    return [d for d in scored if d['score'] > 0][:limit]


def phase2_enriched_rank(query_tokens: list[str],
                         phase1_results: list[dict],
                         top_n: int) -> list[tuple[float, str]]:
    """Phase 2: BM25 over subject files with field weights."""
    documents = []

    for result in phase1_results:
        cat = result["cat"]
        cat_dir = cat["path"]

        for subj_info in cat["parsed"]["subjects"]:
            filename = subj_info["file"]
            if not filename.endswith('.md'):
                filename += '.md'
            subj_path = cat_dir / filename

            if not subj_path.is_file():
                continue

            fm = parse_subject_frontmatter(subj_path)
            subject_stem = filename[:-3] if filename.endswith('.md') else filename

            # Build weighted token list
            # title/aliases: 3x, tags: 2x, summary: 1.5x (approximated as 1x + 0.5x)
            tokens = []

            # Title tokens (3x)
            title_tokens = tokenize(fm["title"])
            tokens.extend(title_tokens * 3)

            # Aliases tokens (3x)
            for alias in fm["aliases"]:
                alias_tokens = tokenize(alias)
                tokens.extend(alias_tokens * 3)

            # Tags tokens (2x)
            for tag in fm["tags"]:
                tag_tokens = tokenize(tag)
                tokens.extend(tag_tokens * 2)

            # Summary tokens (1.5x ≈ 1x base + repeat half)
            summary_tokens = tokenize(fm["summary"])
            tokens.extend(summary_tokens)
            # Add ~half again for the 0.5x boost
            tokens.extend(summary_tokens[:len(summary_tokens) // 2])

            # Category-level context tokens (1x) — from subject name and best_for
            tokens.extend(tokenize(subj_info["name"]))
            tokens.extend(tokenize(subj_info["best_for"]))

            doc_id = f"{cat['topic']}/{cat['category']}/{subject_stem}"
            documents.append({
                "id": doc_id,
                "tokens": tokens,
                "token_set": set(tokens),
            })

    scored = bm25_score(query_tokens, documents)
    results = []
    for d in scored:
        if d['score'] > 0:
            results.append((round(d['score'], 2), d['id']))
    return results[:top_n]


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) < 2:
        sys.exit(0)

    query = sys.argv[1]
    kb_root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('.kb')
    top_n = int(sys.argv[3]) if len(sys.argv) > 3 else 10

    if not kb_root.is_dir():
        sys.exit(0)

    query_tokens = tokenize(query)
    if not query_tokens:
        sys.exit(0)

    categories = discover_categories(kb_root)
    if not categories:
        sys.exit(0)

    # Phase 1: coarse ranking — keep top_n * 3 categories, capped at 30
    phase1_limit = min(top_n * 3, 30)
    phase1_results = phase1_coarse_rank(query_tokens, categories, phase1_limit)

    if not phase1_results:
        sys.exit(0)

    # Phase 2: enriched ranking over subject files
    results = phase2_enriched_rank(query_tokens, phase1_results, top_n)

    for score, doc_id in results:
        print(f"{score:.2f}  {doc_id}")


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Scripts must exit 0 always
        sys.exit(0)
