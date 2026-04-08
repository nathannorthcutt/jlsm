#!/usr/bin/env node
/**
 * Stage 2: Parse token streams into pipeline-aware AST.
 *
 * Reads a TokenStream (from tokenizer.js) and produces a Story AST with
 * typed nodes representing vallorcine pipeline structure.
 *
 * This module knows about vallorcine's pipeline semantics — command names,
 * stage progression, agent roles, file conventions. It does NOT know about
 * rendering or presentation.
 */

"use strict";

const pathMod = require("path");
const { Token, TokenStream, TokenUsage, Node, NodeType, Story } = require("./model.js");

// ---------------------------------------------------------------------------
// Stage mapping
// ---------------------------------------------------------------------------

const TDD_KEYWORDS = ["test-implement", "tdd", "wu-"];

const COMMAND_TO_STAGE = {
  "/feature": "scoping",
  "/feature-quick": "scoping",
  "/feature-domains": "domains",
  "/feature-plan": "planning",
  "/feature-test": "testing",
  "/feature-implement": "implementation",
  "/feature-refactor": "refactor",
  "/feature-coordinate": "coordination",
  "/feature-pr": "pr",
  "/feature-retro": "retro",
  "/feature-complete": "complete",
  "/feature-resume": "resume",
  "/curate": "curation",
  "/research": "research",
  "/architect": "architect",
  "/kb": "knowledge",
  "/decisions": "decisions",
};

function isTddDescription(desc) {
  const lower = desc.toLowerCase();
  return TDD_KEYWORDS.some((kw) => lower.includes(kw));
}

function hasSlug(nodes, slug) {
  for (const n of nodes) {
    if ((n.content || "").includes(slug)) return true;
    for (const v of Object.values(n.data)) {
      if (typeof v === "string" && v.includes(slug)) return true;
    }
    if (n.children && n.children.length && hasSlug(n.children, slug)) return true;
  }
  return false;
}

function detectStoryType(commands) {
  for (const cmd of commands) {
    if (cmd.startsWith("/feature")) return "feature";
    if (cmd === "/curate") return "curation";
    if (cmd === "/research") return "research";
    if (cmd === "/architect") return "architect";
  }
  return "feature";
}

// ---------------------------------------------------------------------------
// Duration helpers
// ---------------------------------------------------------------------------

function parseTs(ts) {
  if (!ts) return null;
  try {
    const d = new Date(ts);
    return isNaN(d.getTime()) ? null : d;
  } catch {
    return null;
  }
}

function durationBetween(ts1, ts2) {
  const t1 = parseTs(ts1);
  const t2 = parseTs(ts2);
  if (t1 && t2) return Math.max(0, t2.getTime() - t1.getTime());
  return 0;
}

const ASSISTANT_TYPES = new Set(["agent_prose", "tool_call", "subagent_start", "subagent_result"]);
const USER_TYPES = new Set(["user_text", "command"]);

function computeIdleTime(tokens) {
  let idleMs = 0;
  let prevType = "";
  let prevTs = "";
  const pendingSubagents = new Map(); // tool_use_id -> timestamp

  for (const t of tokens) {
    if (!t.timestamp) continue;

    // Crash gap
    if (prevType === "session_end" && t.type === "session_start") {
      idleMs += durationBetween(prevTs, t.timestamp);
    }
    // User wait
    else if (ASSISTANT_TYPES.has(prevType) && USER_TYPES.has(t.type)) {
      idleMs += durationBetween(prevTs, t.timestamp);
    }

    // Track subagent lifecycle
    if (t.type === "subagent_start") {
      const saId = t.metadata.tool_use_id || t.metadata.description || "";
      pendingSubagents.set(saId, t.timestamp);
    } else if (t.type === "subagent_result") {
      const desc = t.metadata.description || "";
      let matched = false;
      for (const [saId] of pendingSubagents) {
        if (desc.includes(saId) || saId.includes(desc)) {
          pendingSubagents.delete(saId);
          matched = true;
          break;
        }
      }
      if (!matched && pendingSubagents.size === 1) {
        const [key] = pendingSubagents.keys();
        pendingSubagents.delete(key);
      }
      idleMs += t.metadata.user_wait_ms || 0;
    }

    // Crashed subagent
    if (t.type === "session_end" && pendingSubagents.size > 0) {
      for (const [, launchTs] of pendingSubagents) {
        idleMs += durationBetween(launchTs, t.timestamp);
      }
      pendingSubagents.clear();
    }

    prevType = t.type;
    prevTs = t.timestamp;
  }

  return idleMs;
}

// ---------------------------------------------------------------------------
// Token stream segmentation
// ---------------------------------------------------------------------------

function segmentByCommand(tokens) {
  const segments = [];
  let currentCmd = new Token({ type: "command", metadata: { name: "_preamble", args: "" } });
  let currentTokens = [];

  for (const token of tokens) {
    if (token.type === "command") {
      if (currentTokens.length || (currentCmd.metadata || {}).name !== "_preamble") {
        segments.push([currentCmd, currentTokens]);
      }
      currentCmd = token;
      currentTokens = [];
    } else {
      currentTokens.push(token);
    }
  }

  if (currentTokens.length) {
    segments.push([currentCmd, currentTokens]);
  }

  return segments;
}

// ---------------------------------------------------------------------------
// Pattern recognizers
// ---------------------------------------------------------------------------

function recognizeConversation(tokens, start) {
  const exchanges = [];
  let i = start;

  while (i < tokens.length) {
    if (tokens[i].type === "agent_prose") {
      const question = tokens[i].content;
      let j = i + 1;
      while (j < tokens.length && (tokens[j].type === "tool_call" || tokens[j].type === "tool_result")) {
        j++;
      }
      if (j < tokens.length && tokens[j].type === "user_text") {
        exchanges.push({
          question: (question || "").slice(0, 500),
          answer: (tokens[j].content || "").slice(0, 500),
          timestamp: tokens[i].timestamp,
        });
        i = j + 1;
        continue;
      }
    }
    break;
  }

  if (exchanges.length >= 2) {
    const node = new Node({
      node_type: NodeType.CONVERSATION,
      data: { exchange_count: exchanges.length },
      children: exchanges.map((ex) =>
        new Node({
          node_type: NodeType.EXCHANGE,
          data: ex,
          content: `Q: ${(ex.question || "").slice(0, 100)}\nA: ${(ex.answer || "").slice(0, 100)}`,
        })
      ),
    });
    node.duration_ms = durationBetween(
      exchanges[0].timestamp || "",
      exchanges[exchanges.length - 1].timestamp || ""
    );
    return [node, i];
  }

  return [null, start];
}

function recognizeResearch(tokens, start) {
  if (start >= tokens.length) return [null, start];

  let i = start;
  let topic = "";
  let subject = "";
  let kbPath = "";
  const findings = [];

  while (i < tokens.length) {
    const t = tokens[i];
    if (t.type === "agent_prose") {
      const content = t.content || "";
      if (content.includes("RESEARCH AGENT") || content.includes("Pre-flight check")) {
        const topicMatch = content.match(/Topic:\s*(\S+)/);
        const subjectMatch = content.match(/Subject:\s*(\S+)/);
        if (topicMatch) topic = topicMatch[1];
        if (subjectMatch) subject = subjectMatch[1];
      }
      if (content.includes("RESEARCH AGENT complete")) {
        findings.push(content.slice(0, 500));
        i++;
        break;
      }
    } else if (t.type === "tool_call") {
      const target = (t.metadata || {}).target || "";
      if (target.includes(".kb/") && (t.metadata || {}).tool === "write") {
        kbPath = target;
      }
    }
    i++;
  }

  if (kbPath || topic) {
    return [
      new Node({
        node_type: NodeType.RESEARCH,
        data: {
          topic,
          subject,
          kb_path: kbPath ? pathMod.basename(kbPath) : "",
          findings_summary: findings[0] || "",
        },
        duration_ms: durationBetween(
          start < tokens.length ? tokens[start].timestamp : "",
          tokens[Math.min(i - 1, tokens.length - 1)].timestamp
        ),
      }),
      i,
    ];
  }

  return [null, start];
}

function recognizeArchitect(tokens, start) {
  let i = start;
  let question = "";
  let decision = "";
  let adrPath = "";
  let candidates = [];
  let hasDeliberation = false;

  while (i < tokens.length) {
    const t = tokens[i];
    if (t.type === "agent_prose") {
      const content = t.content || "";
      if (content.includes("ARCHITECT AGENT") && !content.includes("complete")) {
        const qMatch = content.match(/RECOMMENDATION.*?—\s*(.+?)$/m);
        if (qMatch) question = qMatch[1];
      }
      if (content.includes("RECOMMENDATION")) {
        const recMatch = content.match(/Recommended approach:\s*(.+?)$/m);
        const rejMatch = content.match(/Rejected:\s*(.+?)$/m);
        if (recMatch) decision = recMatch[1].trim();
        if (rejMatch) candidates = rejMatch[1].split(",").map((c) => c.trim());
        hasDeliberation = true;
      }
      if (content.includes("ARCHITECT AGENT complete")) {
        i++;
        break;
      }
    } else if (t.type === "tool_call") {
      const target = (t.metadata || {}).target || "";
      if (target.includes("adr.md")) adrPath = target;
    }
    i++;
  }

  if (hasDeliberation || adrPath) {
    return [
      new Node({
        node_type: NodeType.ARCHITECT,
        data: {
          question,
          decision,
          rejected: candidates,
          adr_path: adrPath ? pathMod.basename(adrPath) : "",
        },
        interesting: candidates.length > 0,
        duration_ms: durationBetween(
          tokens[start].timestamp,
          tokens[Math.min(i - 1, tokens.length - 1)].timestamp
        ),
      }),
      i,
    ];
  }

  return [null, start];
}

function recognizeSubagent(tokens, start) {
  if (start >= tokens.length || tokens[start].type !== "subagent_start") {
    return [null, start];
  }

  const desc = (tokens[start].metadata || {}).description || "";
  const nodeType = isTddDescription(desc) ? NodeType.TDD_CYCLE : NodeType.ACTION_GROUP;
  let i = start + 1;
  while (i < tokens.length) {
    const t = tokens[i];
    if (t.type === "subagent_result" && (t.metadata || {}).description === desc) {
      const meta = t.metadata || {};
      return [
        new Node({
          node_type: nodeType,
          data: {
            unit_name: desc,
            duration_ms: meta.duration_ms || 0,
            files_written: meta.files_written || [],
            test_passed: meta.test_passed || 0,
            test_failed: meta.test_failed || 0,
            interesting_types: meta.interesting_types || [],
            summary: meta.summary || "",
          },
          interesting: meta.interesting || false,
          duration_ms: meta.duration_ms || 0,
          tokens: t.tokens,
        }),
        i + 1,
      ];
    }
    if (t.type === "command") break;
    i++;
  }

  return [null, start];
}

// ---------------------------------------------------------------------------
// Phase builder
// ---------------------------------------------------------------------------

function buildPhase(cmdToken, tokens, featureSlug) {
  const cmdName = (cmdToken.metadata || {}).name || "";
  const cmdArgs = (cmdToken.metadata || {}).args || "";
  const stage = COMMAND_TO_STAGE[cmdName] || cmdName.replace(/^\//, "");

  // Feature filtering
  if (featureSlug) {
    // Normalize: "engine clustering" should match slug "engine-clustering"
    const slugVariants = [featureSlug, featureSlug.replace(/-/g, " ")];
    const argsMatch = cmdArgs && slugVariants.some((v) => cmdArgs.includes(v));
    if (cmdArgs && argsMatch) {
      // explicit match
    } else if (cmdArgs && !argsMatch) {
      return null;
    } else if ((cmdName === "/feature" || cmdName === "/feature-quick") && !cmdArgs) {
      let slugFound = false;
      for (const t of tokens) {
        const content = t.content || "";
        const target = (t.metadata || {}).target || "";
        if (
          content.includes(`· ${featureSlug}`) ||
          content.includes(`— ${featureSlug}`) ||
          content.includes(`.feature/${featureSlug}`) ||
          target.includes(`.feature/${featureSlug}`)
        ) {
          slugFound = true;
          break;
        }
      }
      if (!slugFound) return null;
    } else if (cmdName.startsWith("/feature-")) {
      // pipeline continuation, keep
    } else if (cmdName === "/research" || cmdName === "/architect") {
      // invoked during domains, keep
    } else {
      return null;
    }
  }

  let title = stage.charAt(0).toUpperCase() + stage.slice(1);
  if (cmdArgs) title += ` — ${cmdArgs}`;

  const phase = new Node({
    node_type: NodeType.PHASE,
    data: { stage, command: cmdName, title },
  });

  // Set timestamps
  if (cmdToken.timestamp) phase.data.started = cmdToken.timestamp;
  if (tokens.length) {
    let lastTs = "";
    for (let i = tokens.length - 1; i >= 0; i--) {
      if (tokens[i].timestamp) { lastTs = tokens[i].timestamp; break; }
    }
    if (lastTs && cmdToken.timestamp) {
      const rawDuration = durationBetween(cmdToken.timestamp, lastTs);
      const allTokens = [cmdToken, ...tokens];
      const idleMs = computeIdleTime(allTokens);
      phase.duration_ms = Math.max(0, rawDuration - idleMs);
    }
  }

  // Aggregate tokens
  for (const t of tokens) {
    phase.tokens.add(t.tokens);
  }

  phase.children = parsePhaseChildren(tokens);
  return phase;
}

function parsePhaseChildren(tokens) {
  const children = [];
  let i = 0;

  while (i < tokens.length) {
    const t = tokens[i];

    // Session boundaries
    if (t.type === "session_end") {
      if (i + 1 < tokens.length && tokens[i + 1].type === "session_start") {
        const gap = durationBetween(t.timestamp, tokens[i + 1].timestamp);
        children.push(new Node({
          node_type: NodeType.SESSION_BREAK,
          data: { reason: "crash", gap_duration_ms: gap },
          duration_ms: gap,
        }));
        i += 2;
        continue;
      }
      i++;
      continue;
    }

    if (t.type === "session_start") { i++; continue; }

    // Subagent start/result pairs
    if (t.type === "subagent_start") {
      const [node, newI] = recognizeSubagent(tokens, i);
      if (node) { children.push(node); i = newI; continue; }
    }

    // Orphaned subagent results
    if (t.type === "subagent_result") {
      const meta = t.metadata || {};
      const desc = meta.description || "";
      children.push(new Node({
        node_type: isTddDescription(desc) ? NodeType.TDD_CYCLE : NodeType.ACTION_GROUP,
        data: {
          unit_name: desc,
          summary: meta.summary || "",
          duration_ms: meta.duration_ms || 0,
          files_written: meta.files_written || [],
          test_passed: meta.test_passed || 0,
          test_failed: meta.test_failed || 0,
          interesting_types: meta.interesting_types || [],
        },
        interesting: meta.interesting || false,
        duration_ms: meta.duration_ms || 0,
        tokens: t.tokens,
      }));
      i++;
      continue;
    }

    // Conversations
    if (t.type === "agent_prose") {
      const [convNode, newI] = recognizeConversation(tokens, i);
      if (convNode) { children.push(convNode); i = newI; continue; }
    }

    // Stage transitions
    if (t.type === "command") {
      const cmdName = (t.metadata || {}).name || "";
      children.push(new Node({
        node_type: NodeType.STAGE_TRANSITION,
        data: { command: cmdName, args: (t.metadata || {}).args || "" },
      }));
      i++;
      continue;
    }

    // Test results — batch consecutive
    if (t.type === "tool_result" && (t.metadata || {}).is_test) {
      const results = [t];
      let j = i + 1;
      while (j < tokens.length && tokens[j].type === "tool_result" && (tokens[j].metadata || {}).is_test) {
        results.push(tokens[j]);
        j++;
      }
      const totalPassed = results.reduce((s, r) => s + ((r.metadata || {}).passed || 0), 0);
      const totalFailed = results.reduce((s, r) => s + ((r.metadata || {}).failed || 0), 0);
      children.push(new Node({
        node_type: NodeType.TEST_RESULT,
        data: { passed: totalPassed, failed: totalFailed, run_count: results.length },
        interesting: totalFailed > 0,
      }));
      i = j;
      continue;
    }

    // Tool calls — batch consecutive
    if (t.type === "tool_call") {
      const actions = [t];
      let j = i + 1;
      while (j < tokens.length && tokens[j].type === "tool_call") {
        actions.push(tokens[j]);
        j++;
      }
      children.push(new Node({
        node_type: NodeType.ACTION_GROUP,
        data: {
          actions: actions.map((a) => ({
            verb: (a.metadata || {}).tool || "",
            target: pathMod.basename((a.metadata || {}).target || ""),
            summary: (a.metadata || {}).input_summary || "",
          })),
        },
      }));
      i = j;
      continue;
    }

    // Agent prose
    if (t.type === "agent_prose") {
      const interesting = (t.metadata || {}).interesting || false;
      const reason = (t.metadata || {}).interest_reason || "";
      if (interesting && reason === "escalation") {
        children.push(new Node({
          node_type: NodeType.ESCALATION,
          content: (t.content || "").slice(0, 1000),
          interesting: true,
        }));
      } else {
        children.push(new Node({
          node_type: NodeType.PROSE,
          content: (t.content || "").slice(0, 2000),
          interesting,
          tokens: t.tokens,
        }));
      }
      i++;
      continue;
    }

    // User text outside conversation
    if (t.type === "user_text") {
      children.push(new Node({
        node_type: NodeType.EXCHANGE,
        data: { answer: (t.content || "").slice(0, 500) },
        content: (t.content || "").slice(0, 500),
      }));
      i++;
      continue;
    }

    i++;
  }

  return children;
}

// ---------------------------------------------------------------------------
// Story assembly
// ---------------------------------------------------------------------------

function parseStory(stream, featureSlug, storyType) {
  const segments = segmentByCommand(stream.tokens);

  if (!storyType) {
    const commands = segments.map(([cmd]) => (cmd.metadata || {}).name || "");
    storyType = detectStoryType(commands);
  }

  let phases = [];
  for (const [cmdToken, segTokens] of segments) {
    if ((cmdToken.metadata || {}).name === "_preamble") continue;
    const phase = buildPhase(cmdToken, segTokens, featureSlug);
    if (phase) phases.push(phase);
  }

  // Feature filtering — second pass state machine
  if (featureSlug && storyType === "feature") {
    const filtered = [];
    let inFeature = false;
    const CONTINUATION_STAGES = new Set([
      "domains", "planning", "testing", "implementation",
      "refactor", "coordination", "pr", "retro", "complete",
    ]);
    const PASSTHROUGH_STAGES = new Set(["research", "architect", "knowledge", "decisions"]);

    for (const phase of phases) {
      const stage = phase.data.stage || "";
      const title = phase.data.title || "";

      if (title.includes(featureSlug)) {
        inFeature = true;
      } else if (stage === "scoping") {
        if (hasSlug(phase.children, featureSlug)) {
          inFeature = true;
        } else {
          inFeature = false;
          continue;
        }
      } else if (stage === "resume") {
        inFeature = false;
        continue;
      } else if (PASSTHROUGH_STAGES.has(stage)) {
        // keep if in_feature
      } else if (inFeature && CONTINUATION_STAGES.has(stage)) {
        // pipeline continuation
      } else {
        inFeature = false;
        continue;
      }

      // Terminal stages
      if (inFeature && (stage === "retro" || stage === "complete")) {
        filtered.push(phase);
        inFeature = false;
        continue;
      }

      if (inFeature) filtered.push(phase);
    }
    phases = filtered;
  }

  const story = new Story({
    story_type: storyType,
    title: featureSlug || "",
    sessions: [...stream.sessions],
    project: stream.project,
  });

  // Fill metadata from session_start tokens
  for (const t of stream.tokens) {
    if (t.type === "session_start") {
      if (!story.branch) story.branch = (t.metadata || {}).branch || "";
      if (!story.model) story.model = (t.metadata || {}).model || "";
      if (!story.cli_version) story.cli_version = (t.metadata || {}).cli_version || "";
      if (!story.started) story.started = t.timestamp;
    }
  }

  story.phases = phases;
  story.duration_ms = phases.reduce((s, p) => s + p.duration_ms, 0);
  for (const p of phases) story.tokens.add(p.tokens);

  // Filter sessions to only contributing ones
  if (phases.length) {
    const phaseTimestamps = new Set(
      phases.map((p) => p.data.started || "").filter(Boolean)
    );
    const activeSessions = new Set();
    let currentSessionId = "";
    for (const t of stream.tokens) {
      if (t.type === "session_start") currentSessionId = (t.metadata || {}).session_id || "";
      if (phaseTimestamps.has(t.timestamp) && currentSessionId) {
        activeSessions.add(currentSessionId);
      }
    }
    if (activeSessions.size) {
      story.sessions = story.sessions.filter((s) => activeSessions.has(s));
    }
  }

  return story;
}

module.exports = {
  COMMAND_TO_STAGE,
  parseStory,
  segmentByCommand,
  buildPhase,
  parsePhaseChildren,
  detectStoryType,
  durationBetween,
  computeIdleTime,
};
