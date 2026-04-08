#!/usr/bin/env node
/**
 * Shared data model for the narrative pipeline.
 *
 * Defines two layers:
 * - Tokens: clean events extracted from raw JSONL (stage 1 output)
 * - AST nodes: pipeline-aware semantic structure (stage 2 output)
 *
 * Both layers serialize to/from JSON for file-based intermediates.
 */

"use strict";

const fs = require("fs");

// ---------------------------------------------------------------------------
// Token layer (tokenizer output)
// ---------------------------------------------------------------------------

class TokenUsage {
  constructor({ input = 0, output = 0, cache_read = 0, cache_create = 0 } = {}) {
    this.input = input;
    this.output = output;
    this.cache_read = cache_read;
    this.cache_create = cache_create;
  }

  add(other) {
    this.input += other.input;
    this.output += other.output;
    this.cache_read += other.cache_read;
    this.cache_create += other.cache_create;
  }

  get billable_input() {
    return this.input + this.cache_create;
  }

  get total_context() {
    return this.billable_input + this.cache_read;
  }

  toDict() {
    return {
      input: this.input,
      output: this.output,
      cache_read: this.cache_read,
      cache_create: this.cache_create,
    };
  }

  static fromDict(d) {
    if (!d) return new TokenUsage();
    return new TokenUsage(d);
  }
}

class Token {
  /**
   * A single clean event extracted from JSONL.
   *
   * Token types:
   *   command, user_text, agent_prose, tool_call, tool_result,
   *   subagent_start, subagent_result, session_start, session_end
   */
  constructor({ type, timestamp = "", content = "", metadata = {}, tokens = null } = {}) {
    this.type = type;
    this.timestamp = timestamp;
    this.content = content;
    this.metadata = metadata;
    this.tokens = tokens instanceof TokenUsage ? tokens : new TokenUsage(tokens || {});
  }

  toDict() {
    return {
      type: this.type,
      timestamp: this.timestamp,
      content: this.content,
      metadata: this.metadata,
      tokens: this.tokens.toDict(),
    };
  }

  static fromDict(d) {
    return new Token({
      type: d.type,
      timestamp: d.timestamp || "",
      content: d.content || "",
      metadata: d.metadata || {},
      tokens: TokenUsage.fromDict(d.tokens),
    });
  }
}

class TokenStream {
  constructor({ sessions = [], project = "", tokens = [] } = {}) {
    this.sessions = sessions;
    this.project = project;
    this.tokens = tokens;
  }

  save(path) {
    // Stream tokens one at a time to match Python's memory-efficient serialization
    const fd = fs.openSync(path, "w");
    try {
      fs.writeSync(fd, '{\n  "sessions": ');
      fs.writeSync(fd, JSON.stringify(this.sessions));
      fs.writeSync(fd, ',\n  "project": ');
      fs.writeSync(fd, JSON.stringify(this.project));
      fs.writeSync(fd, ',\n  "tokens": [\n');
      for (let i = 0; i < this.tokens.length; i++) {
        if (i > 0) fs.writeSync(fd, ",\n");
        fs.writeSync(fd, JSON.stringify(this.tokens[i].toDict()));
      }
      fs.writeSync(fd, "\n  ]\n}");
    } finally {
      fs.closeSync(fd);
    }
  }

  static load(path) {
    const data = JSON.parse(fs.readFileSync(path, "utf8"));
    return new TokenStream({
      sessions: data.sessions || [],
      project: data.project || "",
      tokens: (data.tokens || []).map((t) => Token.fromDict(t)),
    });
  }
}

// ---------------------------------------------------------------------------
// AST layer (parser output)
// ---------------------------------------------------------------------------

class Node {
  constructor({
    node_type,
    duration_ms = 0,
    tokens = null,
    data = {},
    children = [],
    content = "",
    interesting = false,
  } = {}) {
    this.node_type = node_type;
    this.duration_ms = duration_ms;
    this.tokens = tokens instanceof TokenUsage ? tokens : new TokenUsage(tokens || {});
    this.data = data;
    this.children = children;
    this.content = content;
    this.interesting = interesting;
  }
}

/**
 * AST node types.
 *
 * Composite: PHASE, CONVERSATION, TDD_CYCLE
 * Leaf: EXCHANGE, BRIEF, RESEARCH, ARCHITECT, WORK_PLAN, STAGE_TRANSITION,
 *       SESSION_BREAK, PR, RETRO, ACTION_GROUP, TEST_RESULT, ESCALATION,
 *       PROSE, METRIC
 */
const NodeType = {
  PHASE: "phase",
  CONVERSATION: "conversation",
  TDD_CYCLE: "tdd_cycle",
  EXCHANGE: "exchange",
  BRIEF: "brief",
  RESEARCH: "research",
  ARCHITECT: "architect",
  WORK_PLAN: "work_plan",
  STAGE_TRANSITION: "stage_transition",
  SESSION_BREAK: "session_break",
  PR: "pr",
  RETRO: "retro",
  ACTION_GROUP: "action_group",
  TEST_RESULT: "test_result",
  ESCALATION: "escalation",
  PROSE: "prose",
  METRIC: "metric",
};

class Story {
  constructor({
    story_type,
    title = "",
    project = "",
    branch = "",
    model = "",
    cli_version = "",
    vallorcine_version = "",
    sessions = [],
    started = "",
    duration_ms = 0,
    tokens = null,
    phases = [],
  } = {}) {
    this.story_type = story_type;
    this.title = title;
    this.project = project;
    this.branch = branch;
    this.model = model;
    this.cli_version = cli_version;
    this.vallorcine_version = vallorcine_version;
    this.sessions = sessions;
    this.started = started;
    this.duration_ms = duration_ms;
    this.tokens = tokens instanceof TokenUsage ? tokens : new TokenUsage(tokens || {});
    this.phases = phases;
  }

  save(path) {
    const serialize = (obj) => {
      if (!(obj instanceof Node)) return obj;
      const d = { node_type: obj.node_type };
      if (obj.duration_ms) d.duration_ms = obj.duration_ms;
      if (obj.tokens && (obj.tokens.input || obj.tokens.output)) {
        d.tokens = obj.tokens.toDict();
      }
      if (obj.data && Object.keys(obj.data).length) d.data = obj.data;
      if (obj.children && obj.children.length) {
        d.children = obj.children.map(serialize);
      }
      if (obj.content) d.content = obj.content;
      if (obj.interesting) d.interesting = obj.interesting;
      return d;
    };

    const data = {
      story_type: this.story_type,
      title: this.title,
      project: this.project,
      branch: this.branch,
      model: this.model,
      cli_version: this.cli_version,
      vallorcine_version: this.vallorcine_version,
      sessions: this.sessions,
      started: this.started,
      duration_ms: this.duration_ms,
      tokens: this.tokens.toDict(),
      phases: this.phases.map(serialize),
    };
    fs.writeFileSync(path, JSON.stringify(data, null, 2));
  }

  static load(path) {
    const data = JSON.parse(fs.readFileSync(path, "utf8"));

    const deserializeNode = (d) => {
      return new Node({
        node_type: d.node_type,
        duration_ms: d.duration_ms || 0,
        tokens: TokenUsage.fromDict(d.tokens),
        data: d.data || {},
        children: (d.children || []).map(deserializeNode),
        content: d.content || "",
        interesting: d.interesting || false,
      });
    };

    return new Story({
      story_type: data.story_type,
      title: data.title || "",
      project: data.project || "",
      branch: data.branch || "",
      model: data.model || "",
      cli_version: data.cli_version || "",
      vallorcine_version: data.vallorcine_version || "",
      sessions: data.sessions || [],
      started: data.started || "",
      duration_ms: data.duration_ms || 0,
      tokens: TokenUsage.fromDict(data.tokens),
      phases: (data.phases || []).map(deserializeNode),
    });
  }
}

module.exports = { TokenUsage, Token, TokenStream, Node, NodeType, Story };
