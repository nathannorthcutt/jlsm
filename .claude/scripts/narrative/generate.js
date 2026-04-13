#!/usr/bin/env node
/**
 * Narrative generation orchestrator.
 *
 * Chains the 3-stage pipeline (tokenize → parse → render) for a given
 * feature slug and writes a polished markdown article to the feature
 * directory.
 *
 * This script is invoked by narrative-wrapper.sh as an enhanced
 * implementation of Step 6 in /feature-retro. If the pipeline fails
 * for any reason (missing JSONL, format changes, parse errors), it
 * logs a diagnostic message to stderr and exits 0 — narrative
 * generation is optional and must never block the retro.
 *
 * Usage:
 *     node generate.js <slug> <feature-dir>
 */

"use strict";

const fs = require("fs");
const path = require("path");
const os = require("os");

function findProjectsDir() {
  const projectDir = process.cwd();
  const projectId = projectDir.replace(/^\//, "").replace(/\//g, "-");
  const sessionDir = path.join(os.homedir(), ".claude", "projects", `-${projectId}`);
  try {
    if (fs.existsSync(sessionDir) && fs.statSync(sessionDir).isDirectory()) {
      return sessionDir;
    }
  } catch {
    // fall through
  }
  return "";
}

function readVallorcineVersion() {
  const candidates = [
    path.join(process.cwd(), ".claude", ".vallorcine-version"),
    // Fallback: VERSION file in the kit repo (for dev mode)
    path.resolve(__dirname, "..", "..", "VERSION"),
  ];
  for (const p of candidates) {
    try {
      return fs.readFileSync(p, "utf8").trim();
    } catch {
      continue;
    }
  }
  return "";
}

function generate(slug, featureDir) {
  const { tokenizeFeature } = require("./tokenizer.js");
  const { parseStory } = require("./parse.js");
  const { renderStory } = require("./render_narrative.js");

  const projectsDir = findProjectsDir();
  if (!projectsDir) {
    process.stderr.write("narrative: no Claude Code session directory found\n");
    return false;
  }

  if (!fs.existsSync(featureDir) || !fs.statSync(featureDir).isDirectory()) {
    process.stderr.write(`narrative: feature directory not found: ${featureDir}\n`);
    return false;
  }

  const tokensFile = path.join(featureDir, ".narrative-tokens.json");
  const astFile = path.join(featureDir, ".narrative-ast.json");
  const outputFile = path.join(featureDir, "narrative.md");

  try {
    // Stage 1: Tokenize
    const stream = tokenizeFeature(slug, projectsDir);
    if (!stream.tokens.length) {
      process.stderr.write(`narrative: no sessions found for slug '${slug}'\n`);
      return false;
    }
    stream.save(tokensFile);

    // Stage 2: Parse
    const story = parseStory(stream, slug);
    if (!story.phases.length) {
      process.stderr.write(`narrative: no phases parsed for slug '${slug}'\n`);
      return false;
    }

    // Inject version metadata
    story.vallorcine_version = readVallorcineVersion();
    story.save(astFile);

    // Stage 3: Render
    const markdown = renderStory(story);
    fs.writeFileSync(outputFile, markdown);

    return true;
  } finally {
    // Cleanup intermediates
    for (const f of [tokensFile, astFile]) {
      try { fs.unlinkSync(f); } catch { /* ignore */ }
    }
  }
}

function main() {
  if (process.argv.length < 4) {
    process.stderr.write(`Usage: ${process.argv[1]} <slug> <feature-dir>\n`);
    process.exit(1);
  }

  const slug = process.argv[2];
  const featureDir = process.argv[3];

  try {
    const success = generate(slug, featureDir);
    if (success) {
      process.stderr.write(`narrative: wrote ${path.join(featureDir, "narrative.md")}\n`);
      process.exit(0);
    } else {
      process.exit(1);
    }
  } catch (e) {
    process.stderr.write(`narrative: pipeline failed: ${e.message}\n`);
    process.stderr.write(e.stack + "\n");
    process.exit(1);
  }
}

main();
