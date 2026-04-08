#!/usr/bin/env node
/**
 * Stage 3: Render a Story AST into a polished narrative markdown article.
 *
 * Consumes the AST produced by parse.js and generates a visually rich markdown
 * document using shields.io badges, Mermaid diagrams, progressive disclosure,
 * and structured conversation turns.
 *
 * Design principles:
 *   1. Density gradient — scoping compressed, TDD/architecture expandable
 *   2. Interruptions are the story — escalations, failures are prominent
 *   3. Two reading modes — skim (badges + headlines) and deep dive (<details>)
 *   4. Color semantics — green=passing, red=failing, amber=escalation, blue=refactor
 */

"use strict";

const fs = require("fs");
const { Node, NodeType, Story, TokenUsage } = require("./model.js");

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

function formatDuration(ms) {
  if (ms <= 0) return "< 1s";
  const seconds = ms / 1000;
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = seconds / 60;
  if (minutes < 60) {
    const secs = Math.round(seconds % 60);
    if (secs) return `${Math.floor(minutes)}m ${secs}s`;
    return `${Math.floor(minutes)}m`;
  }
  const hours = minutes / 60;
  const mins = Math.round(minutes % 60);
  return `${Math.floor(hours)}h ${mins}m`;
}

function abbreviate(n) {
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return String(n);
}

function formatTokensShort(usage) {
  const billable = usage.billable_input;
  if (!billable && !usage.output) return "0";
  return `${abbreviate(billable)} in / ${abbreviate(usage.output)} out`;
}

function formatTokensDetail(usage) {
  const billable = usage.billable_input;
  if (!billable && !usage.output) return "";
  const parts = [`${abbreviate(billable)} in`];
  if (usage.cache_read) parts.push(`(${abbreviate(usage.cache_read)} cached)`);
  parts.push(`/ ${abbreviate(usage.output)} out`);
  return parts.join(" ");
}

function badge(label, message, color) {
  const safeLabel = encodeURIComponent(label.replace(/-/g, "--").replace(/_/g, "__"));
  const safeMsg = encodeURIComponent(message.replace(/-/g, "--").replace(/_/g, "__"));
  return `![${label}](https://img.shields.io/badge/${safeLabel}-${safeMsg}-${color}?style=for-the-badge)`;
}

// ---------------------------------------------------------------------------
// Prose cleaning
// ---------------------------------------------------------------------------

const BOX_CHARS = "─━═│┌┐└┘├┤┬┴┼╔╗╚╝╠╣╦╩╬";
const DECORATION_SET = new Set((BOX_CHARS + " ·—-").split(""));

function isDecorationLine(stripped) {
  if (!stripped) return false;
  if ([...stripped].every((c) => DECORATION_SET.has(c))) return true;
  const boxCount = [...stripped].filter((c) => BOX_CHARS.includes(c)).length;
  if (boxCount > stripped.length * 0.4 && boxCount > 5) return true;
  return false;
}

function hasBoxDrawing(text) {
  return /[─━═│┌┐└┘├┤┬┴┼╔╗╚╝╠╣╦╩╬→←↑↓]/.test(text);
}

const EMOJI_PREFIX_RE = /^[🔍🏛️🗺️🏗️🔀📋🔄🔬⚙️🔧✓⚠️]+\s*/u;
const TRAILING_DECO_RE = /\s*[─━═]{2,}\s*$/;
const LEADING_DECO_RE = /^[─━═]{2,}\s*/;

function cleanAgentProse(text) {
  const lines = text.split("\n");
  let cleaned = [];
  let inBanner = false;
  let i = 0;

  while (i < lines.length) {
    const stripped = lines[i].trim();
    if (isDecorationLine(stripped)) { i++; continue; }
    if (stripped === "```" && !inBanner) { inBanner = true; i++; continue; }
    if (stripped === "```" && inBanner) { inBanner = false; i++; continue; }
    if (inBanner) {
      let clean = stripped.replace(EMOJI_PREFIX_RE, "").replace(TRAILING_DECO_RE, "");
      if (clean) cleaned.push(clean);
      i++;
      continue;
    }
    // Detect ASCII art blocks
    let boxCount = 0, end = i;
    for (let j = i; j < Math.min(i + 10, lines.length); j++) {
      if (hasBoxDrawing(lines[j]) || !lines[j].trim()) { boxCount++; end = j + 1; }
      else break;
    }
    if (boxCount >= 2) { i = end; continue; }

    let cleanLine = stripped.replace(EMOJI_PREFIX_RE, "").replace(TRAILING_DECO_RE, "").replace(LEADING_DECO_RE, "");
    if (cleanLine) cleaned.push(cleanLine);
    i++;
  }

  // Safety: unclosed banner
  if (inBanner) {
    cleaned = [];
    for (const line of lines) {
      const stripped = line.trim();
      if (isDecorationLine(stripped) || stripped === "```") continue;
      let cleanLine = stripped.replace(EMOJI_PREFIX_RE, "").replace(TRAILING_DECO_RE, "").replace(LEADING_DECO_RE, "");
      if (cleanLine) cleaned.push(cleanLine);
    }
  }

  // Remove orphaned headings
  const final = [];
  for (let i = 0; i < cleaned.length; i++) {
    const stripped = cleaned[i].trim();
    if (stripped.startsWith("#") && stripped.length < 80) {
      let hasBody = false;
      for (let j = i + 1; j < cleaned.length; j++) {
        const next = cleaned[j].trim();
        if (!next) continue;
        if (next.startsWith("#")) break;
        hasBody = true;
        break;
      }
      if (!hasBody) continue;
    }
    final.push(cleaned[i]);
  }

  return final.join("\n").trim();
}

function truncateProse(text, maxLines = 30) {
  const lines = text.split("\n");
  if (lines.length <= maxLines) return text;
  const keep = Math.floor(maxLines / 2);
  return [...lines.slice(0, keep), "", "*(continued...)*", "", ...lines.slice(-keep)].join("\n");
}

function smartTruncate(text, maxChars = 280) {
  if (text.length <= maxChars) return [text, false];
  let cut = text.lastIndexOf(" ", maxChars);
  if (cut === -1) cut = maxChars;
  return [text.slice(0, cut), true];
}

function styledDiv(content, fullContent, speaker, bg, border) {
  const lines = [];
  lines.push(`<div style="background:${bg};border-left:4px solid ${border};padding:12px 16px;margin:8px 0;border-radius:4px">`);
  if (content !== fullContent) {
    lines.push(`<details><summary><strong>${speaker}:</strong> ${content.replace(/\n/g, "<br>")}...</summary>`);
    lines.push(`<br>${fullContent.replace(/\n/g, "<br>")}`);
    lines.push("</details>");
  } else {
    lines.push(`<strong>${speaker}:</strong> ${content.replace(/\n/g, "<br>")}`);
  }
  lines.push("</div>");
  lines.push("");
  return lines;
}

// ---------------------------------------------------------------------------
// AST helpers
// ---------------------------------------------------------------------------

const PHASE_EMOJI = {
  scoping: "🔍", domains: "🗺️", planning: "🏗️", testing: "🧪",
  implementation: "⚙️", refactor: "🔧", coordination: "🔀", pr: "📋",
  retro: "🔄", resume: "🔁", curation: "🔬", research: "📚",
  architect: "🏛️", knowledge: "📖", decisions: "⚖️",
};

function countNewTests(story) {
  let total = 0;
  function walk(nodes) {
    for (const n of nodes) {
      if (n.node_type === NodeType.TDD_CYCLE) total += (n.data || {}).test_passed || 0;
      if (n.children && n.children.length) walk(n.children);
    }
  }
  for (const phase of story.phases) walk(phase.children || []);
  return total;
}

// ---------------------------------------------------------------------------
// Render context
// ---------------------------------------------------------------------------

class RenderContext {
  constructor(story) {
    this.story = story;
    this.current_phase = "";
    this.phase_index = 0;
    this.test_total_passed = 0;
    this.test_total_failed = 0;
  }
}

// ---------------------------------------------------------------------------
// Node renderers
// ---------------------------------------------------------------------------

const CONFIRMATION_RESPONSES = new Set([
  "yes", "y", "no", "n", "ok", "okay", "sure", "continue",
  "create", "auto", "stop", "done", "skip", "proceed",
]);

function isConfirmation(text) {
  return CONFIRMATION_RESPONSES.has(text.trim().replace(/[.!]$/, "").toLowerCase());
}

function renderConversation(node, ctx) {
  const lines = [];
  const isScoping = ctx.current_phase === "scoping";
  if (!isScoping) {
    const count = (node.data || {}).exchange_count || (node.children || []).length;
    lines.push(`<details><summary><b>Conversation</b> — ${count} exchanges</summary>`, "", "<br>", "");
  }
  for (const child of node.children || []) {
    const q = (child.data || {}).question || "";
    const a = (child.data || {}).answer || "";
    if (q) {
      const qClean = cleanAgentProse(q);
      if (!qClean.trim()) continue;
      const [qShort, qTruncated] = smartTruncate(qClean);
      lines.push(...styledDiv(qShort, qTruncated ? qClean : qShort, "vallorcine", "#1e3a5f", "#4184e4"));
    }
    if (a && !isConfirmation(a)) {
      const [aShort, aTruncated] = smartTruncate(a);
      lines.push(...styledDiv(aShort, aTruncated ? a : aShort, "User", "#1a3a2a", "#3fb950"));
    }
  }
  if (!isScoping) { lines.push("</details>", ""); }
  return lines;
}

function renderExchange(node, ctx) {
  const answer = (node.data || {}).answer || node.content || "";
  if (!answer || isConfirmation(answer)) return [];
  const [aShort, aTruncated] = smartTruncate(answer);
  return styledDiv(aShort, aTruncated ? answer : aShort, "User", "#1a3a2a", "#3fb950");
}

function renderTddCycle(node, ctx) {
  if (!node.interesting) return [];
  const lines = [];
  const name = (node.data || {}).unit_name || "Work unit";
  const duration = formatDuration((node.data || {}).duration_ms || 0);
  const summary = (node.data || {}).summary || "";
  const types = (node.data || {}).interesting_types || [];
  const passed = (node.data || {}).test_passed || 0;
  const failed = (node.data || {}).test_failed || 0;

  let bg, border;
  if (types.includes("escalation") || failed > 0) {
    bg = "#3d2f1f"; border = "#d29922";
  } else {
    bg = "#1e3a5f"; border = "#4184e4";
  }

  const bodyParts = [`<strong>${name}</strong> — ${duration}`];
  if (failed) bodyParts.push(`Tests: ${passed} passed, ${failed} failed`);
  else if (passed) bodyParts.push(`Tests: ${passed} passed`);
  if (types.length) bodyParts.push(`Signals: ${types.join(", ")}`);
  if (summary) {
    const parts = summary.split(" — ").map((p) => p.trim());
    for (const part of parts.slice(1)) bodyParts.push(`• ${part}`);
  }

  lines.push(`<div style="background:${bg};border-left:4px solid ${border};padding:12px 16px;margin:8px 0;border-radius:4px">`);
  lines.push(bodyParts.join("<br>"));
  lines.push("</div>", "");
  return lines;
}

function renderTddSummaryTable(cycles, ctx) {
  if (!cycles.length) return [];
  const storyTotal = (ctx.story.tokens.billable_input + ctx.story.tokens.output) || 1;
  const lines = [
    "| Work Unit | Duration | Tests | Files | % of total |",
    "|-----------|----------|-------|-------|------------|",
  ];
  for (const c of cycles) {
    const name = (c.data || {}).unit_name || "—";
    const duration = formatDuration((c.data || {}).duration_ms || 0);
    const passed = (c.data || {}).test_passed || 0;
    const files = ((c.data || {}).files_written || []).length;
    const tests = passed ? `✅ ${passed}` : "—";
    const wuTotal = c.tokens.billable_input + c.tokens.output;
    const pct = (wuTotal / storyTotal) * 100;
    lines.push(`| ${name} | ${duration} | ${tests} | ${files} | ${Math.round(pct)}% |`);
  }
  lines.push("");
  return lines;
}

function renderResearch(node, ctx) {
  const topic = (node.data || {}).topic || "";
  const subject = (node.data || {}).subject || "";
  const kbPath = (node.data || {}).kb_path || "";
  const findings = (node.data || {}).findings_summary || "";

  let titleParts = ["<strong>📚 Research"];
  if (topic) titleParts.push(`: ${topic}`);
  titleParts.push("</strong>");

  const bodyParts = [];
  if (subject) bodyParts.push(`Subject: ${subject}`);
  if (kbPath) bodyParts.push(`KB: <code>${kbPath}</code>`);
  if (findings) {
    const cleaned = cleanAgentProse(findings);
    if (cleaned) bodyParts.push(truncateProse(cleaned, 8).replace(/\n/g, "<br>"));
  }

  const lines = [
    '<div style="background:#1a3a2a;border-left:4px solid #3fb950;padding:12px 16px;margin:8px 0;border-radius:4px">',
    titleParts.join(""),
  ];
  if (bodyParts.length) lines.push("<br>" + bodyParts.join("<br>"));
  lines.push("</div>", "");
  return lines;
}

function renderArchitect(node, ctx) {
  const question = (node.data || {}).question || "";
  const decision = (node.data || {}).decision || "";
  const rejected = (node.data || {}).rejected || [];
  const adrPath = (node.data || {}).adr_path || "";

  const bodyParts = ["<strong>🏛️ Architecture Decision</strong>"];
  if (question) bodyParts.push(`<strong>Question:</strong> ${question}`);
  if (decision) bodyParts.push(`<strong>Decision:</strong> ${decision}`);
  if (rejected.length) {
    const struck = rejected.map((r) => `<del>${r}</del>`).join(", ");
    bodyParts.push(`<strong>Rejected:</strong> ${struck}`);
  }
  if (adrPath) bodyParts.push(`<strong>ADR:</strong> <code>${adrPath}</code>`);

  return [
    '<div style="background:#1e3a5f;border-left:4px solid #4184e4;padding:12px 16px;margin:8px 0;border-radius:4px">',
    bodyParts.join("<br>"),
    "</div>", "",
  ];
}

function renderStageTransition(node, ctx) {
  const cmd = (node.data || {}).command || "";
  const args = (node.data || {}).args || "";
  if (!cmd) return [];
  let display = `\`${cmd}\``;
  if (args) display += ` ${args}`;
  return [`**→ ${display}**`, ""];
}

function renderSessionBreak(node, ctx) {
  const gap = formatDuration((node.data || {}).gap_duration_ms || 0);
  return [
    '<div style="background:#3d1f1f;border-left:4px solid #f85149;padding:12px 16px;margin:8px 0;border-radius:4px">',
    `⚠️ <strong>Session crashed.</strong> Resumed ${gap} later.`,
    "</div>", "",
  ];
}

const TDD_PHASES = new Set(["testing", "implementation", "refactor", "coordination", "resume"]);

function renderTestResult(node, ctx) {
  if (!TDD_PHASES.has(ctx.current_phase)) return [];
  const passed = (node.data || {}).passed || 0;
  const failed = (node.data || {}).failed || 0;
  const runs = (node.data || {}).run_count || 1;
  ctx.test_total_passed += passed;
  ctx.test_total_failed += failed;
  if (failed) {
    return [
      '<div style="background:#3d2f1f;border-left:4px solid #d29922;padding:12px 16px;margin:8px 0;border-radius:4px">',
      `⚠️ Tests: ${passed} passed, ${failed} failed (${runs} runs)`,
      "</div>", "",
    ];
  }
  return [`✅ Tests: ${passed} passed (${runs} runs)`, ""];
}

function renderEscalation(node, ctx) {
  const content = node.content || "";
  const cleaned = content ? cleanAgentProse(content) : "";
  if (ctx.current_phase === "retro" && cleaned) return renderRetroChecklist(cleaned);
  const body = cleaned ? "<br>" + truncateProse(cleaned, 10).replace(/\n/g, "<br>") : "";
  return [
    '<div style="background:#3d2f1f;border-left:4px solid #d29922;padding:12px 16px;margin:8px 0;border-radius:4px">',
    `⚠️ <strong>Escalation</strong>${body}`,
    "</div>", "",
  ];
}

function renderRetroChecklist(text) {
  const metItems = [];
  const deferredItems = [];
  const otherLines = [];

  for (const line of text.split("\n")) {
    const stripped = line.trim();
    if (/^\d+\./.test(stripped) && stripped.includes("—")) {
      const lower = stripped.replace(/\*\*/g, "").toLowerCase();
      if (/— deferred|— not met|— skipped/.test(lower)) deferredItems.push(stripped);
      else if (/— met|— yes|— passed/.test(lower)) metItems.push(stripped);
    } else {
      otherLines.push(stripped);
    }
  }

  const lines = [];

  // Scope line
  const scopeLine = otherLines.find((l) => /scope:|drift/i.test(l));
  if (scopeLine) {
    lines.push('<div style="background:#1e3a5f;border-left:4px solid #4184e4;padding:12px 16px;margin:8px 0;border-radius:4px">');
    lines.push(`🔍 <strong>Retrospective</strong><br>${scopeLine.trim()}`);
    lines.push("</div>", "");
  }

  if (metItems.length) {
    const itemsHtml = metItems.map((item) => `✅ ${item}`).join("<br>");
    lines.push('<div style="background:#1a3a2a;border-left:4px solid #3fb950;padding:12px 16px;margin:8px 0;border-radius:4px">');
    lines.push(`<strong>Acceptance Criteria</strong><br><br>${itemsHtml}`);
    lines.push("</div>", "");
  }

  if (deferredItems.length) {
    const itemsHtml = deferredItems.map((item) => `⏸️ ${item}`).join("<br>");
    lines.push('<div style="background:#1e3a5f;border-left:4px solid #4184e4;padding:12px 16px;margin:8px 0;border-radius:4px">');
    lines.push(`<strong>Deferred</strong><br><br>${itemsHtml}`);
    lines.push("</div>", "");
  }

  const substantive = otherLines.filter((l) =>
    l.trim() &&
    !l.trim().endsWith(":") &&
    !/^\*\*.*\*\*:?$/.test(l.trim()) &&
    !/^(FEATURE RETRO|SCOPING AGENT|DOMAIN SCOUT|RESEARCH AGENT|ARCHITECT AGENT|COORDINATOR)/.test(l.trim()) &&
    l.trim().length >= 20 &&
    !l.toLowerCase().includes("let me analyze")
  );
  if (substantive.length) {
    const remainingClean = cleanAgentProse(substantive.join("\n"));
    if (remainingClean.trim()) {
      const summary = remainingClean.split("\n")[0].slice(0, 100);
      const bodyHtml = remainingClean.replace(/\n/g, "<br>");
      lines.push(`<details><summary>📋 <b>${summary}...</b></summary>`, "", "<br>", "", bodyHtml, "", "</details>", "");
    }
  }

  return lines;
}

function renderActionGroup(node, ctx) {
  if ("unit_name" in (node.data || {})) {
    const name = (node.data || {}).unit_name || "Action";
    const duration = formatDuration((node.data || {}).duration_ms || 0);
    const summary = (node.data || {}).summary || "";
    let detail = "";
    if (summary) {
      const parts = summary.split(" — ").map((p) => p.trim());
      detail = parts.length > 1 ? parts.slice(1).join(" — ") : "";
    }
    if (detail) return [`> **${name}** *(${duration})* — ${detail}`, ""];
    return [`> **${name}** *(${duration})*`, ""];
  }

  const actions = (node.data || {}).actions || [];
  if (!actions.length) return [];

  if (actions.length <= 3) {
    const items = actions.map((a) => a.summary || `${a.verb || "?"} ${a.target || ""}`);
    return [`📝 *${items.join("; ")}*`, ""];
  }

  const lines = [`<details><summary>📝 <b>${actions.length} file operations</b></summary>`, "", "<br>", ""];
  for (const a of actions) lines.push(`- \`${a.verb || ""}\` ${a.target || ""}`);
  lines.push("", "</details>", "");
  return lines;
}

function renderProse(node, ctx) {
  const content = node.content || "";
  if (!content.trim()) return [];
  const cleaned = cleanAgentProse(content);
  if (!cleaned.trim()) return [];

  if (node.interesting) return [truncateProse(cleaned, 30), ""];

  const cleanedLines = cleaned.split("\n");
  if (cleanedLines.length <= 5) return [cleaned, ""];

  const summary = cleanedLines[0].slice(0, 120);
  let bodyLines = cleanedLines.slice(1);
  while (bodyLines.length && !bodyLines[0].trim()) bodyLines.shift();
  const body = truncateProse(bodyLines.join("\n"), 40);

  const lines = [`<details><summary>${summary}...</summary>`, "", "<br>", ""];
  for (const bl of body.split("\n")) {
    lines.push(bl.trim() ? `> ${bl}` : ">");
  }
  lines.push("", "</details>", "");
  return lines;
}

// ---------------------------------------------------------------------------
// Dispatch table
// ---------------------------------------------------------------------------

const RENDERERS = {
  [NodeType.CONVERSATION]: renderConversation,
  [NodeType.EXCHANGE]: renderExchange,
  [NodeType.TDD_CYCLE]: renderTddCycle,
  [NodeType.RESEARCH]: renderResearch,
  [NodeType.ARCHITECT]: renderArchitect,
  [NodeType.STAGE_TRANSITION]: renderStageTransition,
  [NodeType.SESSION_BREAK]: renderSessionBreak,
  [NodeType.TEST_RESULT]: renderTestResult,
  [NodeType.ESCALATION]: renderEscalation,
  [NodeType.ACTION_GROUP]: renderActionGroup,
  [NodeType.PROSE]: renderProse,
};

function renderNode(node, ctx) {
  const renderer = RENDERERS[node.node_type];
  if (renderer) return renderer(node, ctx);
  if (node.content) return [(node.content || "").slice(0, 500), ""];
  return [];
}

// ---------------------------------------------------------------------------
// Prominent / background classification
// ---------------------------------------------------------------------------

const PROMINENT_TYPES = new Set([
  NodeType.CONVERSATION, NodeType.EXCHANGE, NodeType.ESCALATION,
  NodeType.SESSION_BREAK, NodeType.RESEARCH, NodeType.ARCHITECT,
]);

const BACKGROUND_TYPES = new Set([
  NodeType.PROSE, NodeType.ACTION_GROUP, NodeType.TEST_RESULT,
  NodeType.STAGE_TRANSITION,
]);

// ---------------------------------------------------------------------------
// Phase renderer
// ---------------------------------------------------------------------------

function renderPhase(node, ctx) {
  const stage = (node.data || {}).stage || "";
  let title = (node.data || {}).title || (stage.charAt(0).toUpperCase() + stage.slice(1));
  const duration = formatDuration(node.duration_ms);
  ctx.current_phase = stage;

  const storyTitle = ctx.story.title;
  if (storyTitle && title.includes(`— ${storyTitle}`)) {
    title = title.replace(` — ${storyTitle}`, "").trim();
  }

  const emoji = PHASE_EMOJI[stage] || "📌";
  const anchorId = `phase-${ctx.phase_index}`;
  ctx.phase_index++;
  const lines = [`<a id="${anchorId}"></a>`, "", `## ${emoji} ${title}`, ""];

  const metrics = [`*Time: ${duration}*`];
  const tokenStr = formatTokensDetail(node.tokens);
  if (tokenStr) metrics.push(`*Tokens: ${tokenStr}*`);
  lines.push(metrics.join(" | "), "");

  // Categorize children
  const routineCycles = [], interestingCycles = [], prominent = [], background = [];
  for (const child of node.children || []) {
    if (child.node_type === NodeType.TDD_CYCLE) {
      (child.interesting ? interestingCycles : routineCycles).push(child);
    } else if (PROMINENT_TYPES.has(child.node_type)) {
      prominent.push(child);
    } else {
      background.push(child);
    }
  }

  for (const child of prominent) lines.push(...renderNode(child, ctx));
  for (const cycle of interestingCycles) lines.push(...renderTddCycle(cycle, ctx));
  if (routineCycles.length) lines.push(...renderTddSummaryTable(routineCycles, ctx));

  if (background.length) {
    const bgLines = [];
    for (const child of background) {
      const rendered = renderNode(child, ctx);
      if (rendered.length) bgLines.push(...rendered);
    }
    if (bgLines.length) {
      const fileOps = background.filter((c) => c.node_type === NodeType.ACTION_GROUP).length;
      const proseCount = background.filter((c) => c.node_type === NodeType.PROSE).length;
      const summaryParts = [];
      if (proseCount) summaryParts.push(`${proseCount} steps`);
      if (fileOps) summaryParts.push(`${fileOps} file operations`);
      const summaryLabel = summaryParts.length ? summaryParts.join(", ") : "details";

      lines.push(`<details><summary>📋 <b>Show ${summaryLabel}</b></summary>`, "", "<br>", "");
      lines.push(...bgLines);
      lines.push("</details>", "");
    }
  }

  return lines;
}

// ---------------------------------------------------------------------------
// Hero section
// ---------------------------------------------------------------------------

function renderHero(story, ctx) {
  const lines = [];
  const title = story.title || "Session";
  const st = story.story_type;
  if (st === "feature") lines.push(`# Building \`${title}\` with vallorcine`);
  else if (st === "curation") lines.push(`# Curating \`${title}\``);
  else if (st === "research") lines.push(`# Researching \`${title}\``);
  else if (st === "architect") lines.push(`# Architecture: \`${title}\``);
  else lines.push(`# \`${title}\``);
  lines.push("");

  const duration = formatDuration(story.duration_ms);
  const tokensShort = formatTokensShort(story.tokens);
  const newTests = countNewTests(story);
  const model = story.model || "unknown";
  const sessions = story.sessions.length;
  const cliVersion = story.cli_version || "";
  const vallorcineVersion = story.vallorcine_version || "";

  const badges = [
    badge("duration", duration, "blue"),
    badge("tokens", tokensShort, "blueviolet"),
    badge("model", model, "lightgrey"),
  ];
  if (cliVersion) badges.push(badge("claude code", `v${cliVersion}`, "lightgrey"));
  if (vallorcineVersion) badges.push(badge("vallorcine", `v${vallorcineVersion}`, "orange"));
  if (newTests) badges.push(badge("new tests", String(newTests), "brightgreen"));
  if (sessions > 1) badges.push(badge("sessions", `${sessions} (crash recovery)`, "yellow"));

  lines.push(badges.join(" "), "");

  // Mermaid gantt
  if (story.phases.length > 1) {
    lines.push("```mermaid");
    lines.push("%%{init: {'theme': 'dark', 'themeVariables': {" +
      "'sectionBkgColor': '#2d333b', 'sectionBkgColor2': '#1c2128'," +
      "'taskBkgColor': '#4184e4', 'activeTaskBkgColor': '#4184e4'," +
      "'taskTextColor': '#e6edf3', 'taskTextDarkColor': '#e6edf3'," +
      "'altSectionBkgColor': '#1c2128'" +
      "}}}%%");
    lines.push("gantt");
    lines.push("    title Pipeline (h:mm)");
    lines.push("    dateFormat YYYY-MM-DD HH:mm");
    lines.push("    axisFormat %H:%M");

    const DISCOVERY = new Set(["scoping", "domains", "research", "architect", "knowledge", "decisions"]);
    const EXECUTION = new Set(["planning", "testing", "implementation", "refactor", "coordination"]);
    const DELIVERY = new Set(["pr", "retro", "complete"]);

    let currentSection = "";
    let cumulativeMin = 0;

    function ganttTs(minutes) {
      const days = Math.floor(minutes / 1440);
      const remaining = minutes % 1440;
      const hours = Math.floor(remaining / 60);
      const mins = remaining % 60;
      return `2000-01-${String(1 + days).padStart(2, "0")} ${String(hours).padStart(2, "0")}:${String(mins).padStart(2, "0")}`;
    }

    for (let i = 0; i < story.phases.length; i++) {
      const phase = story.phases[i];
      const stage = (phase.data || {}).stage || `phase-${i}`;
      let section;
      if (DISCOVERY.has(stage)) section = "Discovery";
      else if (EXECUTION.has(stage)) section = "Execution";
      else if (DELIVERY.has(stage)) section = "Delivery";
      else section = currentSection || "Execution";

      if (section !== currentSection) {
        lines.push(`    section ${section}`);
        currentSection = section;
      }

      const label = stage.charAt(0).toUpperCase() + stage.slice(1);
      const durationMin = Math.max(1, Math.floor(phase.duration_ms / 60000));
      lines.push(`    ${label} :p${i}, ${ganttTs(cumulativeMin)}, ${ganttTs(cumulativeMin + durationMin)}`);

      // Work unit bars
      const tddCycles = (phase.children || []).filter((c) => c.node_type === NodeType.TDD_CYCLE);
      if (tddCycles.length && (stage === "coordination" || stage === "planning")) {
        let wuOffset = cumulativeMin;
        for (let j = 0; j < tddCycles.length; j++) {
          const wu = tddCycles[j];
          const wuName = (wu.data || {}).unit_name || `WU-${j + 1}`;
          const shortName = wuName.includes(" ") ? wuName.split(" ")[0] : wuName;
          const wuDur = Math.max(1, Math.floor(((wu.data || {}).duration_ms || 0) / 60000));
          lines.push(`    ${shortName} :p${i}w${j}, ${ganttTs(wuOffset)}, ${ganttTs(wuOffset + wuDur)}`);
          wuOffset += wuDur;
        }
      }

      cumulativeMin += durationMin;
    }

    lines.push("```", "");
  }

  // Phase breakdown table
  if (story.phases.length) {
    const totalIn = story.tokens.billable_input || 1;
    const totalOut = story.tokens.output || 1;

    lines.push("### Phase Breakdown", "");
    lines.push("| Phase | Duration | Tokens (in) | Tokens (out) | % of total |");
    lines.push("|-------|----------|-------------|--------------|------------|");
    for (let i = 0; i < story.phases.length; i++) {
      const phase = story.phases[i];
      const stage = (phase.data || {}).stage || "—";
      const emoji = PHASE_EMOJI[stage] || "";
      let phTitle = (phase.data || {}).title || (stage.charAt(0).toUpperCase() + stage.slice(1));
      if (story.title && phTitle.includes(`— ${story.title}`)) {
        phTitle = phTitle.replace(` — ${story.title}`, "").trim();
      }
      const dur = formatDuration(phase.duration_ms);
      const inp = abbreviate(phase.tokens.billable_input);
      const out = abbreviate(phase.tokens.output);
      const phaseTotal = phase.tokens.billable_input + phase.tokens.output;
      const storyTotal = totalIn + (story.tokens.output || 1);
      const pct = (phaseTotal / storyTotal) * 100;
      lines.push(`| [${emoji} ${phTitle}](#phase-${i}) | ${dur} | ${inp} | ${out} | ${Math.round(pct)}% |`);
    }
    lines.push("");
  }

  lines.push("---", "");
  return lines;
}

// ---------------------------------------------------------------------------
// Footer
// ---------------------------------------------------------------------------

function renderFooter() {
  return ["---", "", "*Generated by [vallorcine](https://github.com/telefrek/vallorcine) showcase tools.*"];
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

function renderStory(story) {
  const ctx = new RenderContext(story);
  const lines = [];
  lines.push(...renderHero(story, ctx));
  for (const phase of story.phases) {
    lines.push(...renderPhase(phase, ctx));
    lines.push("");
  }
  lines.push(...renderFooter());
  return lines.join("\n");
}

// CLI
function main() {
  const args = process.argv.slice(2);
  if (!args.length) {
    process.stderr.write("Usage: render_narrative.js <story-file> [-o output]\n");
    process.exit(1);
  }
  const storyFile = args[0];
  const outputIdx = args.indexOf("-o");
  const outputFile = outputIdx >= 0 ? args[outputIdx + 1] : null;

  const story = Story.load(storyFile);
  const markdown = renderStory(story);

  if (outputFile) {
    fs.writeFileSync(outputFile, markdown);
    process.stderr.write(`Wrote ${markdown.length} bytes to ${outputFile}\n`);
  } else {
    process.stdout.write(markdown);
  }
}

if (require.main === module) main();

module.exports = { renderStory, formatDuration, formatTokensShort, formatTokensDetail, badge };
