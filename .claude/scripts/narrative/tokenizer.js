#!/usr/bin/env node
/**
 * Stage 1: Tokenize Claude Code JSONL session logs into clean event streams.
 *
 * Reads raw JSONL files, filters noise (progress events, file snapshots),
 * extracts clean tokens with timestamps, content, and metadata. Handles
 * multi-session stitching for feature stories and subagent discovery.
 *
 * This module knows about Claude Code's JSONL format but NOT about
 * vallorcine's pipeline semantics — that's the parser's job.
 */

"use strict";

const fs = require("fs");
const path = require("path");
const os = require("os");
const { Token, TokenStream, TokenUsage } = require("./model.js");

// ---------------------------------------------------------------------------
// JSONL format guards
// ---------------------------------------------------------------------------

const EXPECTED_FIELDS = new Set(["type", "timestamp", "message"]);
let formatWarned = false;

function checkFormat(entry, source) {
  if (formatWarned) return true;
  const keys = Object.keys(entry);
  if (!keys.some((k) => EXPECTED_FIELDS.has(k))) {
    formatWarned = true;
    process.stderr.write(
      `narrative: JSONL format may have changed — entry in ${source} ` +
      `has none of the expected fields (${[...EXPECTED_FIELDS].sort().join(", ")}). ` +
      `Found keys: ${keys.sort().slice(0, 10).join(", ")}. ` +
      `Report at https://github.com/telefrek/vallorcine/issues\n`
    );
    return false;
  }
  const msg = entry.message;
  if (msg !== undefined && msg !== null && typeof msg !== "object") {
    formatWarned = true;
    process.stderr.write(
      `narrative: JSONL format may have changed — 'message' field in ${source} ` +
      `is ${typeof msg}, expected object. ` +
      `Report at https://github.com/telefrek/vallorcine/issues\n`
    );
    return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// JSONL helpers
// ---------------------------------------------------------------------------

function iterJsonl(filePath) {
  let content;
  try {
    content = fs.readFileSync(filePath, "utf8");
  } catch {
    return [];
  }
  const results = [];
  for (const line of content.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      results.push(JSON.parse(trimmed));
    } catch {
      continue;
    }
  }
  return results;
}

function flattenToolResultContent(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((item) =>
        typeof item === "object" && item !== null
          ? item.text || JSON.stringify(item)
          : String(item)
      )
      .join("\n");
  }
  return String(content);
}

function extractCommand(content) {
  const nameMatch = content.match(/<command-name>([^<]+)<\/command-name>/);
  const argsMatch = content.match(/<command-args>([\s\S]*?)<\/command-args>/);
  const name = nameMatch ? nameMatch[1] : null;
  let args = argsMatch ? argsMatch[1].trim().replace(/^"|"$/g, "") : null;
  return [name, args];
}

function extractUsage(entry) {
  const usage = (entry.message || {}).usage || {};
  return new TokenUsage({
    input: usage.input_tokens || 0,
    output: usage.output_tokens || 0,
    cache_read: usage.cache_read_input_tokens || 0,
    cache_create: usage.cache_creation_input_tokens || 0,
  });
}

function tsDiffMs(ts1, ts2) {
  try {
    const t1 = new Date(ts1).getTime();
    const t2 = new Date(ts2).getTime();
    return Math.max(0, t2 - t1);
  } catch {
    return 0;
  }
}

function isBookkeepingFile(filePath) {
  const basename = path.basename(filePath);
  if (basename === "status.md") return true;
  if (basename === "CLAUDE.md" && (filePath.includes(".kb/") || filePath.includes(".decisions/"))) {
    return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Interesting moment detection
// ---------------------------------------------------------------------------

const INTEREST_LITERALS = [
  ["escalat", "escalation"],
  ["contract conflict", "escalation"],
  ["can't satisfy", "escalation"],
  ["cannot satisfy", "escalation"],
  ["flagged for review", "refactor_finding"],
  ["retry", "retry"],
  ["second attempt", "retry"],
  ["trying again", "retry"],
];

const INTEREST_REGEX = [
  [/doesn't match.*contract/, "escalation"],
  [/structural (issue|concern)/, "refactor_finding"],
  [/security (issue|concern|finding)/, "refactor_finding"],
  [/pause.*human/, "refactor_finding"],
  [/checklist item 2[cd]/, "refactor_finding"],
  [/cycle [2-9]/, "retry"],
];

function detectInterest(text) {
  const lower = text.toLowerCase();
  for (const [substring, category] of INTEREST_LITERALS) {
    if (lower.includes(substring)) return [true, category];
  }
  for (const [pattern, category] of INTEREST_REGEX) {
    if (pattern.test(lower)) return [true, category];
  }
  return [false, null];
}

// ---------------------------------------------------------------------------
// Subagent processing
// ---------------------------------------------------------------------------

function tokenizeSubagent(jsonlPath, metaPath) {
  let meta = {};
  try {
    if (fs.existsSync(metaPath)) {
      meta = JSON.parse(fs.readFileSync(metaPath, "utf8"));
    }
  } catch {
    // corrupt meta file — continue with empty meta
  }

  let firstTs = "";
  let lastTs = "";
  const tokens = new TokenUsage();
  const filesWritten = [];
  const interestingMoments = [];
  let testPassed = 0;
  let testFailed = 0;
  let hasEntries = false;
  let prevMsgType = "";
  let prevTs = "";
  let userWaitMs = 0;

  for (const entry of iterJsonl(jsonlPath)) {
    hasEntries = true;
    const ts = entry.timestamp || "";
    if (ts && !firstTs) firstTs = ts;
    if (ts) lastTs = ts;

    const msgType = entry.type || "";

    // Detect user wait gaps
    if (prevMsgType && (msgType === "assistant" || msgType === "user") && prevTs && ts) {
      const gap = tsDiffMs(prevTs, ts);
      if (prevMsgType === "assistant" && msgType === "user" && gap > 30000) {
        userWaitMs += gap;
      } else if (prevMsgType === "user" && msgType === "assistant" && gap > 120000) {
        userWaitMs += gap;
      }
    }

    if (msgType === "assistant") {
      tokens.add(extractUsage(entry));
      const contentBlocks = (entry.message || {}).content || [];
      if (!Array.isArray(contentBlocks)) {
        if (ts) { prevMsgType = msgType; prevTs = ts; }
        continue;
      }
      for (const block of contentBlocks) {
        if (block.type === "text") {
          const [interesting, reason] = detectInterest(block.text || "");
          if (interesting) interestingMoments.push(reason);
        } else if (block.type === "tool_use") {
          const name = block.name || "";
          const inp = block.input || {};
          if (name === "Write" || name === "Edit") {
            const fp = inp.file_path || "";
            if (fp && !isBookkeepingFile(fp)) {
              filesWritten.push(path.basename(fp));
            }
          }
        }
      }
    } else if (msgType === "user") {
      const content = (entry.message || {}).content || [];
      if (Array.isArray(content)) {
        for (const block of content) {
          if (block.type === "tool_result") {
            const resultText = flattenToolResultContent(block.content || "");
            const passed = (resultText.match(/(?:PASS|passed|✓|✅|ok)/gi) || []).length;
            const failed = (resultText.match(/(?:FAIL|failed|✗|❌|ERROR)/gi) || []).length;
            testPassed += passed;
            testFailed += failed;
          }
        }
      }
    }

    if (ts && (msgType === "assistant" || msgType === "user")) {
      prevMsgType = msgType;
      prevTs = ts;
    }
  }

  if (!hasEntries) {
    return new Token({
      type: "subagent_result",
      metadata: {
        agent_type: meta.agentType || "unknown",
        description: meta.description || "",
        summary: "(empty subagent session)",
      },
    });
  }

  let durationMs = 0;
  if (firstTs && lastTs) {
    durationMs = Math.max(0, tsDiffMs(firstTs, lastTs) - userWaitMs);
  }

  const desc = meta.description || "subagent";
  const summaryParts = [desc];
  if (filesWritten.length) summaryParts.push(`${filesWritten.length} files written`);
  if (testPassed || testFailed) {
    if (testFailed) {
      summaryParts.push(`tests: ${testPassed} passed, ${testFailed} failed`);
    } else {
      summaryParts.push(`${testPassed} tests passing`);
    }
  }
  if (interestingMoments.length) {
    summaryParts.push(`${interestingMoments.length} interesting moments`);
  }

  return new Token({
    type: "subagent_result",
    timestamp: firstTs,
    content: summaryParts.join(" — "),
    metadata: {
      agent_type: meta.agentType || "unknown",
      description: desc,
      summary: summaryParts.join(" — "),
      duration_ms: durationMs,
      files_written: filesWritten,
      interesting: interestingMoments.length > 0,
      interesting_types: interestingMoments,
      test_passed: testPassed,
      test_failed: testFailed,
      detail_file: jsonlPath,
      user_wait_ms: userWaitMs,
    },
    tokens,
  });
}

// ---------------------------------------------------------------------------
// Session tokenization
// ---------------------------------------------------------------------------

function tokenizeSession(jsonlPath, subagentDir) {
  const sessionId = path.basename(jsonlPath, ".jsonl");
  const tokensOut = [];
  const pendingAgents = {}; // tool_use_id -> description
  let metaIndex = null; // lazy: description -> meta_path
  let sessionStartEmitted = false;
  let lastTs = "";
  let formatChecked = false;

  for (const entry of iterJsonl(jsonlPath)) {
    if (!formatChecked) {
      checkFormat(entry, jsonlPath);
      formatChecked = true;
    }

    const msgType = entry.type || "";
    const ts = entry.timestamp || "";
    if (ts) lastTs = ts;

    // Emit session_start from first entry with timestamp
    if (!sessionStartEmitted && ts) {
      const branch = entry.gitBranch || "";
      const model = (entry.message || {}).model || "";
      const cliVersion = entry.version || "";
      tokensOut.push(new Token({
        type: "session_start",
        timestamp: ts,
        metadata: {
          session_id: sessionId,
          branch: branch || "",
          model: model || "",
          cli_version: cliVersion || "",
        },
      }));
      sessionStartEmitted = true;
    }

    // Backfill model from assistant messages
    if (sessionStartEmitted && msgType === "assistant" && !tokensOut[0].metadata.model) {
      const model = (entry.message || {}).model || "";
      if (model) tokensOut[0].metadata.model = model;
    }

    // Skip noise
    if (["progress", "file-history-snapshot", "last-prompt", "pr-link", "queue-operation", "system"].includes(msgType)) {
      continue;
    }

    if (msgType === "user") {
      const msg = entry.message || {};
      const content = msg.content;

      // Command invocation
      if (typeof content === "string" && content.includes("<command-name>")) {
        const [cmdName, cmdArgs] = extractCommand(content);
        if (cmdName) {
          tokensOut.push(new Token({
            type: "command",
            timestamp: ts,
            content: cmdArgs ? `${cmdName} ${cmdArgs}` : cmdName,
            metadata: { name: cmdName, args: cmdArgs || "" },
          }));
        }
        continue;
      }

      // User text
      if (typeof content === "string" && content.trim()) {
        if (content.includes("<system-reminder>")) continue;
        if (content.includes("<task-notification>")) continue;
        tokensOut.push(new Token({
          type: "user_text",
          timestamp: ts,
          content: content.slice(0, 2000),
        }));
        continue;
      }

      // Tool results
      if (Array.isArray(content)) {
        for (const block of content) {
          if (block.type !== "tool_result") continue;
          const toolUseId = block.tool_use_id || "";
          const resultContent = flattenToolResultContent(block.content || "");

          // Subagent result
          if (toolUseId in pendingAgents && subagentDir) {
            const agentDesc = pendingAgents[toolUseId];
            delete pendingAgents[toolUseId];
            const agentIdMatch = resultContent.match(/agentId[:\s]+['"]?([a-f0-9]+)['"]?/);
            let saToken = null;

            if (agentIdMatch) {
              const rawId = agentIdMatch[1];
              const saJsonl = path.join(subagentDir, `agent-${rawId}.jsonl`);
              const saMeta = path.join(subagentDir, `agent-${rawId}.meta.json`);
              if (fs.existsSync(saJsonl)) {
                saToken = tokenizeSubagent(saJsonl, saMeta);
              }
            }

            // Fallback: lazy description→path index
            if (!saToken && subagentDir) {
              if (metaIndex === null) {
                metaIndex = {};
                try {
                  for (const f of fs.readdirSync(subagentDir)) {
                    if (!f.endsWith(".meta.json")) continue;
                    try {
                      const m = JSON.parse(fs.readFileSync(path.join(subagentDir, f), "utf8"));
                      metaIndex[m.description || ""] = path.join(subagentDir, f);
                    } catch {
                      continue;
                    }
                  }
                } catch {
                  // subagent dir disappeared
                }
              }
              const metaPath = metaIndex[agentDesc];
              if (metaPath) {
                const saId = path.basename(metaPath, ".meta.json");
                const saJsonl = path.join(subagentDir, `${saId}.jsonl`);
                if (fs.existsSync(saJsonl)) {
                  saToken = tokenizeSubagent(saJsonl, metaPath);
                }
              }
            }

            if (saToken) {
              saToken.metadata.description = agentDesc;
              tokensOut.push(saToken);
            } else {
              tokensOut.push(new Token({
                type: "subagent_result",
                timestamp: ts,
                content: resultContent.slice(0, 500),
                metadata: {
                  description: agentDesc,
                  summary: resultContent.slice(0, 300),
                },
              }));
            }
            continue;
          }

          // Test results
          if (/pass|fail|error/i.test(resultContent)) {
            const passed = (resultContent.match(/(?:PASS|passed|✓|✅|ok)/gi) || []).length;
            const failed = (resultContent.match(/(?:FAIL|failed|✗|❌|ERROR)/gi) || []).length;
            if (passed || failed) {
              tokensOut.push(new Token({
                type: "tool_result",
                timestamp: ts,
                content: failed ? `${passed} passed, ${failed} failed` : `${passed} passed`,
                metadata: {
                  tool_use_id: toolUseId,
                  is_test: true,
                  passed,
                  failed,
                },
              }));
            }
          }
        }
      }
    } else if (msgType === "assistant") {
      const msg = entry.message || {};
      const usage = extractUsage(entry);
      const contentBlocks = msg.content || [];
      if (!Array.isArray(contentBlocks)) continue;

      let usageAssigned = false;

      for (const block of contentBlocks) {
        const blockType = block.type || "";

        if (blockType === "text") {
          const text = block.text || "";
          if (!text.trim()) continue;
          const [interesting, reason] = detectInterest(text);
          tokensOut.push(new Token({
            type: "agent_prose",
            timestamp: ts,
            content: text.slice(0, 2000),
            metadata: {
              interesting,
              interest_reason: reason || "",
            },
            tokens: !usageAssigned ? usage : new TokenUsage(),
          }));
          usageAssigned = true;
        } else if (blockType === "tool_use") {
          const toolName = block.name || "";
          const toolInput = block.input || {};
          const toolId = block.id || "";

          // Track Agent calls
          if (toolName === "Agent") {
            const desc = toolInput.description || "";
            pendingAgents[toolId] = desc;
            tokensOut.push(new Token({
              type: "subagent_start",
              timestamp: ts,
              metadata: {
                description: desc,
                agent_type: toolInput.subagent_type || "general-purpose",
                tool_use_id: toolId,
              },
            }));
            continue;
          }

          // Skill invocation
          if (toolName === "Skill") {
            const skill = toolInput.skill || "";
            tokensOut.push(new Token({
              type: "command",
              timestamp: ts,
              content: `/${skill}`,
              metadata: { name: `/${skill}`, args: "" },
            }));
            continue;
          }

          // File writes/edits
          if (toolName === "Write" || toolName === "Edit") {
            const fp = toolInput.file_path || "";
            if (fp && !isBookkeepingFile(fp)) {
              tokensOut.push(new Token({
                type: "tool_call",
                timestamp: ts,
                metadata: {
                  tool: toolName.toLowerCase(),
                  target: fp,
                  input_summary: `${toolName === "Write" ? "Wrote" : "Edited"} ${path.basename(fp)}`,
                },
              }));
            }
            continue;
          }

          // Bash commands (only interesting ones)
          if (toolName === "Bash") {
            const cmd = toolInput.command || "";
            const desc = toolInput.description || "";
            const interestingCmds = ["git commit", "git push", "npm test", "pytest", "go test", "cargo test", "make test", "gh pr"];
            if (interestingCmds.some((kw) => cmd.includes(kw))) {
              tokensOut.push(new Token({
                type: "tool_call",
                timestamp: ts,
                metadata: {
                  tool: "bash",
                  target: cmd.slice(0, 120),
                  input_summary: desc || `Ran: ${cmd.slice(0, 80)}`,
                },
              }));
            } else if (desc && ["test", "commit", "push", "pr"].some((kw) => desc.toLowerCase().includes(kw))) {
              tokensOut.push(new Token({
                type: "tool_call",
                timestamp: ts,
                metadata: {
                  tool: "bash",
                  target: cmd.slice(0, 120),
                  input_summary: desc,
                },
              }));
            }
          }
        }
      }
    }
  }

  // Session end
  if (lastTs) {
    tokensOut.push(new Token({
      type: "session_end",
      timestamp: lastTs,
      metadata: { session_id: sessionId, reason: "end" },
    }));
  }

  return tokensOut;
}

// ---------------------------------------------------------------------------
// Multi-session stitching
// ---------------------------------------------------------------------------

function findProjectDir(projectHint) {
  const claudeDir = path.join(os.homedir(), ".claude", "projects");
  try {
    if (!fs.existsSync(claudeDir)) return null;
    const dirs = fs.readdirSync(claudeDir);
    for (const d of dirs) {
      const full = path.join(claudeDir, d);
      if (fs.statSync(full).isDirectory() && d.includes(projectHint)) return full;
    }
    const hintLower = projectHint.toLowerCase();
    for (const d of dirs) {
      const full = path.join(claudeDir, d);
      if (fs.statSync(full).isDirectory() && d.toLowerCase().includes(hintLower)) return full;
    }
  } catch {
    return null;
  }
  return null;
}

function findSessionsForFeature(projectDir, slug) {
  const slugPattern = new RegExp(
    `(?:["'/_\\s-]|^)${slug.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}(?:["'/_\\s,.\\-\\]}]|$)`
  );
  const sessions = [];
  let files;
  try {
    files = fs.readdirSync(projectDir).filter((f) => f.endsWith(".jsonl")).sort();
  } catch {
    return [];
  }

  for (const f of files) {
    const fullPath = path.join(projectDir, f);
    const content = fs.readFileSync(fullPath, "utf8");
    for (const line of content.split("\n")) {
      if (line.includes(slug) && slugPattern.test(line)) {
        sessions.push(fullPath);
        break;
      }
    }
  }

  function firstTimestamp(filePath) {
    const content = fs.readFileSync(filePath, "utf8");
    for (const line of content.split("\n")) {
      if (!line.trim()) continue;
      try {
        const d = JSON.parse(line);
        if (d.timestamp) return d.timestamp;
      } catch {
        continue;
      }
    }
    return "";
  }

  sessions.sort((a, b) => {
    const ta = firstTimestamp(a);
    const tb = firstTimestamp(b);
    return ta < tb ? -1 : ta > tb ? 1 : 0;
  });
  return sessions;
}

function tokenizeFeature(slug, projectDir) {
  const sessionPaths = findSessionsForFeature(projectDir, slug);
  if (!sessionPaths.length) return new TokenStream();

  const stream = new TokenStream({ project: projectDir });

  for (const p of sessionPaths) {
    const sessionId = path.basename(p, ".jsonl");
    stream.sessions.push(sessionId);

    let subagentDir = path.join(path.dirname(p), sessionId, "subagents");
    if (!fs.existsSync(subagentDir) || !fs.statSync(subagentDir).isDirectory()) {
      subagentDir = null;
    }

    try {
      const sessionTokens = tokenizeSession(p, subagentDir);
      stream.tokens.push(...sessionTokens);
    } catch (e) {
      process.stderr.write(
        `narrative: failed to tokenize session ${sessionId}: ${e.message}. ` +
        `Skipping this session. ` +
        `Report at https://github.com/telefrek/vallorcine/issues\n`
      );
    }
  }

  return stream;
}

function tokenizeSingleSession(sessionId, projectDir) {
  const p = path.join(projectDir, `${sessionId}.jsonl`);
  if (!fs.existsSync(p)) return new TokenStream();

  let subagentDir = path.join(projectDir, sessionId, "subagents");
  if (!fs.existsSync(subagentDir) || !fs.statSync(subagentDir).isDirectory()) {
    subagentDir = null;
  }

  return new TokenStream({
    sessions: [sessionId],
    project: projectDir,
    tokens: tokenizeSession(p, subagentDir),
  });
}

module.exports = {
  iterJsonl,
  flattenToolResultContent,
  extractCommand,
  extractUsage,
  tsDiffMs,
  isBookkeepingFile,
  detectInterest,
  tokenizeSubagent,
  tokenizeSession,
  findProjectDir,
  findSessionsForFeature,
  tokenizeFeature,
  tokenizeSingleSession,
};
