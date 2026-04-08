"""Shared data model for the showcase pipeline.

Defines two layers:
- Tokens: clean events extracted from raw JSONL (stage 1 output)
- AST nodes: pipeline-aware semantic structure (stage 2 output)

Both layers serialize to/from JSON for file-based intermediates.
"""

from dataclasses import dataclass, field
import json


# ---------------------------------------------------------------------------
# Token layer (tokenizer output)
# ---------------------------------------------------------------------------

@dataclass
class TokenUsage:
    """Token counts for a single API call or aggregated span."""
    input: int = 0
    output: int = 0
    cache_read: int = 0
    cache_create: int = 0

    def add(self, other: "TokenUsage"):
        """Accumulate another usage into this one (mutates in place)."""
        self.input += other.input
        self.output += other.output
        self.cache_read += other.cache_read
        self.cache_create += other.cache_create

    @property
    def billable_input(self) -> int:
        return self.input + self.cache_create

    @property
    def total_context(self) -> int:
        return self.billable_input + self.cache_read


@dataclass
class Token:
    """A single clean event extracted from JSONL.

    Token types:
        command         — slash command invocation (/feature, /research, etc.)
        user_text       — user typed something (not a command)
        agent_prose     — assistant text block
        tool_call       — assistant invoked a tool (with name + input summary)
        tool_result     — result came back from a tool
        subagent_start  — Agent tool invoked (with description)
        subagent_result — Agent tool returned (with summary, agent_id)
        session_start   — first event in a session
        session_end     — last event in a session (or crash boundary)
    """
    type: str
    timestamp: str = ""
    content: str = ""
    metadata: dict = field(default_factory=dict)
    # metadata keys by type:
    #   command: {name, args}
    #   tool_call: {tool, input_summary, target}
    #   tool_result: {tool_use_id, is_error}
    #   subagent_start: {description, agent_type, tool_use_id}
    #   subagent_result: {agent_id, description, summary, duration_ms,
    #                     files_written, interesting, detail_file}
    #   session_start: {session_id, branch, model, project}
    #   session_end: {session_id, reason}
    tokens: TokenUsage = field(default_factory=TokenUsage)


@dataclass
class TokenStream:
    """Ordered sequence of tokens from one or more sessions."""
    sessions: list[str] = field(default_factory=list)
    project: str = ""
    tokens: list[Token] = field(default_factory=list)

    def save(self, path: str):
        """Write token stream to a JSON file.

        Serializes tokens one at a time to avoid the 2x memory spike
        from asdict() deep-copying the entire stream.
        """
        with open(path, "w") as f:
            f.write('{\n  "sessions": ')
            json.dump(self.sessions, f)
            f.write(',\n  "project": ')
            json.dump(self.project, f)
            f.write(',\n  "tokens": [\n')
            for i, t in enumerate(self.tokens):
                if i > 0:
                    f.write(',\n')
                json.dump({
                    "type": t.type,
                    "timestamp": t.timestamp,
                    "content": t.content,
                    "metadata": t.metadata,
                    "tokens": {
                        "input": t.tokens.input,
                        "output": t.tokens.output,
                        "cache_read": t.tokens.cache_read,
                        "cache_create": t.tokens.cache_create,
                    },
                }, f)
            f.write('\n  ]\n}')

    @classmethod
    def load(cls, path: str) -> "TokenStream":
        """Load token stream from a JSON file."""
        with open(path) as f:
            data = json.load(f)
        stream = cls(
            sessions=data.get("sessions", []),
            project=data.get("project", ""),
        )
        for t in data.get("tokens", []):
            usage = t.get("tokens", {})
            stream.tokens.append(Token(
                type=t["type"],
                timestamp=t.get("timestamp", ""),
                content=t.get("content", ""),
                metadata=t.get("metadata", {}),
                tokens=TokenUsage(**usage) if usage else TokenUsage(),
            ))
        return stream


# ---------------------------------------------------------------------------
# AST layer (parser output)
# ---------------------------------------------------------------------------

@dataclass
class Node:
    """Base AST node. All nodes have a type, optional duration/tokens,
    and optional children for composite nodes."""
    node_type: str
    duration_ms: int = 0
    tokens: TokenUsage = field(default_factory=TokenUsage)
    # Structured data specific to each node type
    data: dict = field(default_factory=dict)
    # Child nodes for composite types
    children: list["Node"] = field(default_factory=list)
    # Original content for Prose fallback nodes
    content: str = ""
    # Flag for narrative interest
    interesting: bool = False


# Node type constants
class NodeType:
    """AST node types.

    Composite nodes (have children):
        PHASE           — pipeline stage (scoping, domains, planning, etc.)
        CONVERSATION    — sequence of exchanges between user and agent
        TDD_CYCLE       — one work unit's test→implement→refactor cycle
        AUDIT_CYCLE     — audit round (contains AUDIT_FINDING children)

    Leaf nodes (data in .data dict):
        EXCHANGE        — single question + answer pair
        BRIEF           — feature brief presentation + confirmation
        RESEARCH        — research commission (topic, findings, kb_path)
        ARCHITECT       — architecture decision session
        WORK_PLAN       — work unit decomposition
        STAGE_TRANSITION — command invocation moving to next stage
        SESSION_BREAK   — crash/resume boundary
        PR              — pull request created
        RETRO           — retrospective summary
        ACTION_GROUP    — batch of file writes/edits
        TEST_RESULT     — test pass/fail summary
        ESCALATION      — contract conflict, test failure requiring intervention
        PROSE           — unclassified agent output (fallback)
        METRIC          — computed metric (tokens, duration, test count)
        AUDIT_FINDING   — individual audit finding (confirmed fix or impossible)
    """
    PHASE = "phase"
    CONVERSATION = "conversation"
    TDD_CYCLE = "tdd_cycle"
    EXCHANGE = "exchange"
    BRIEF = "brief"
    RESEARCH = "research"
    ARCHITECT = "architect"
    WORK_PLAN = "work_plan"
    STAGE_TRANSITION = "stage_transition"
    SESSION_BREAK = "session_break"
    PR = "pr"
    RETRO = "retro"
    ACTION_GROUP = "action_group"
    TEST_RESULT = "test_result"
    ESCALATION = "escalation"
    PROSE = "prose"
    METRIC = "metric"
    AUDIT_CYCLE = "audit_cycle"
    AUDIT_FINDING = "audit_finding"


@dataclass
class Story:
    """Root AST node — the complete narrative.

    Contains metadata (type, project, branch, model, timing, token usage)
    and an ordered list of Phase nodes representing pipeline stages.
    Serializes to/from JSON for file-based handoff to renderers.
    """
    story_type: str  # feature, curation, research, architect
    title: str = ""
    project: str = ""
    branch: str = ""
    model: str = ""
    cli_version: str = ""
    vallorcine_version: str = ""
    sessions: list[str] = field(default_factory=list)
    started: str = ""
    duration_ms: int = 0
    tokens: TokenUsage = field(default_factory=TokenUsage)
    phases: list[Node] = field(default_factory=list)

    @staticmethod
    def _usage_dict(u: "TokenUsage") -> dict:
        """Convert TokenUsage to dict without asdict() deep-copy overhead."""
        return {
            "input": u.input, "output": u.output,
            "cache_read": u.cache_read, "cache_create": u.cache_create,
        }

    def save(self, path: str):
        """Write story AST to a JSON file.

        Uses manual dict construction instead of dataclasses.asdict()
        to avoid deep-copying the entire AST tree. Nodes with zero-value
        fields (no duration, no tokens, no children) omit those keys
        to keep the output compact.
        """
        def _serialize(obj):
            if isinstance(obj, Node):
                d = {"node_type": obj.node_type}
                if obj.duration_ms:
                    d["duration_ms"] = obj.duration_ms
                if obj.tokens and (obj.tokens.input or obj.tokens.output):
                    d["tokens"] = Story._usage_dict(obj.tokens)
                if obj.data:
                    d["data"] = obj.data
                if obj.children:
                    d["children"] = [_serialize(c) for c in obj.children]
                if obj.content:
                    d["content"] = obj.content
                if obj.interesting:
                    d["interesting"] = obj.interesting
                return d
            return obj

        data = {
            "story_type": self.story_type,
            "title": self.title,
            "project": self.project,
            "branch": self.branch,
            "model": self.model,
            "cli_version": self.cli_version,
            "vallorcine_version": self.vallorcine_version,
            "sessions": self.sessions,
            "started": self.started,
            "duration_ms": self.duration_ms,
            "tokens": Story._usage_dict(self.tokens),
            "phases": [_serialize(p) for p in self.phases],
        }
        with open(path, "w") as f:
            json.dump(data, f, indent=2)

    @classmethod
    def load(cls, path: str) -> "Story":
        """Load story AST from a JSON file."""
        with open(path) as f:
            data = json.load(f)

        def _deserialize_node(d: dict) -> Node:
            tokens_data = d.get("tokens", {})
            return Node(
                node_type=d["node_type"],
                duration_ms=d.get("duration_ms", 0),
                tokens=TokenUsage(**tokens_data) if tokens_data else TokenUsage(),
                data=d.get("data", {}),
                children=[_deserialize_node(c) for c in d.get("children", [])],
                content=d.get("content", ""),
                interesting=d.get("interesting", False),
            )

        tokens_data = data.get("tokens", {})
        story = cls(
            story_type=data["story_type"],
            title=data.get("title", ""),
            project=data.get("project", ""),
            branch=data.get("branch", ""),
            model=data.get("model", ""),
            cli_version=data.get("cli_version", ""),
            vallorcine_version=data.get("vallorcine_version", ""),
            sessions=data.get("sessions", []),
            started=data.get("started", ""),
            duration_ms=data.get("duration_ms", 0),
            tokens=TokenUsage(**tokens_data) if tokens_data else TokenUsage(),
            phases=[_deserialize_node(p) for p in data.get("phases", [])],
        )
        return story
