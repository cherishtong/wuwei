-- Wuwei Core Schema v7.0.0

PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA foreign_keys=ON;

-- Skill registry
CREATE TABLE IF NOT EXISTS skill_registry (
    id               TEXT PRIMARY KEY,
    version          TEXT NOT NULL,
    runtime          TEXT NOT NULL,
    abi              TEXT NOT NULL DEFAULT '1.0',
    capabilities_json TEXT NOT NULL,
    source           TEXT DEFAULT 'local',
    install_time     INTEGER DEFAULT (strftime('%s','now')),
    last_run         INTEGER
);

-- Skill state metadata (actual KV in per-skill phenotype/state.db)
CREATE TABLE IF NOT EXISTS skill_state_meta (
    skill_id   TEXT NOT NULL,
    key_count  INTEGER DEFAULT 0,
    db_size_kb INTEGER DEFAULT 0,
    updated_at INTEGER DEFAULT (strftime('%s','now')),
    PRIMARY KEY (skill_id)
);

-- State snapshots for crash recovery + hot reload
CREATE TABLE IF NOT EXISTS skill_snapshot (
    skill_id         TEXT PRIMARY KEY,
    version          TEXT NOT NULL,
    abi_version      TEXT NOT NULL DEFAULT '1.0',
    snapshot_time    INTEGER NOT NULL,
    reason           TEXT,
    ui_tree_json     TEXT NOT NULL,
    state_summary    TEXT
);

-- Operation log (audit + Phase 2 Event Replay)
CREATE TABLE IF NOT EXISTS op_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id    TEXT NOT NULL,
    op_type     TEXT NOT NULL,
    event_id    TEXT,
    payload     TEXT,
    timestamp   INTEGER DEFAULT (strftime('%s','now')),
    device_id   TEXT,
    synced      INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_op_log_skill_time
    ON op_log(skill_id, timestamp);

-- Capability usage audit
CREATE TABLE IF NOT EXISTS capability_audit (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id    TEXT NOT NULL,
    cap_name    TEXT NOT NULL,
    method      TEXT NOT NULL,
    args_summary TEXT,
    result      TEXT,
    timestamp   INTEGER DEFAULT (strftime('%s','now'))
);

-- Evolution pressure log (Phase 2)
CREATE TABLE IF NOT EXISTS ep_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id    TEXT NOT NULL,
    type        TEXT NOT NULL,
    trigger     TEXT,
    intensity   INTEGER,
    timestamp   INTEGER
);

-- Trace log (Phase 2 Event Replay, keep last 200 per skill)
CREATE TABLE IF NOT EXISTS trace_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_id        TEXT NOT NULL,
    event_id        TEXT NOT NULL,
    inputs_json     TEXT,
    patches_json    TEXT,
    latency_ms      INTEGER,
    timestamp       INTEGER DEFAULT (strftime('%s','now'))
);

CREATE INDEX IF NOT EXISTS idx_trace_skill_time
    ON trace_log(skill_id, timestamp);

-- Model routing (which model to use per task type)
CREATE TABLE IF NOT EXISTS model_routing (
    task_type  TEXT PRIMARY KEY,   -- 'skill/generate' | 'skill/repair' | 'skill/drift' | 'ai/ask'
    provider   TEXT NOT NULL,      -- 'openai' | 'anthropic' | 'deepseek' | 'google'
    model      TEXT NOT NULL,      -- 'gpt-4o' | 'claude-sonnet-4-20250514' | ...
    updated_at INTEGER DEFAULT (strftime('%s','now'))
);

-- Default routing (inserted only if table is empty)
INSERT OR IGNORE INTO model_routing(task_type, provider, model) VALUES
    ('skill/generate',  'openai',    'gpt-4o'),
    ('skill/repair',    'openai',    'gpt-4o-mini'),
    ('skill/drift',     'openai',    'gpt-4o-mini'),
    ('ai/ask',          'openai',    'gpt-4o-mini');

-- LLM usage log (token + cost tracking per call)
CREATE TABLE IF NOT EXISTS model_usage_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_type     TEXT NOT NULL,
    provider      TEXT NOT NULL,
    model         TEXT NOT NULL,
    input_tokens  INTEGER DEFAULT 0,
    output_tokens INTEGER DEFAULT 0,
    latency_ms    INTEGER DEFAULT 0,
    cost          REAL DEFAULT 0.0,
    created_at    INTEGER DEFAULT (strftime('%s','now'))
);
