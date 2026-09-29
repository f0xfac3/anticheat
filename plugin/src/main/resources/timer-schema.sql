PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS trials (
 id TEXT PRIMARY KEY, seed INTEGER NOT NULL, condition TEXT NOT NULL,
 client TEXT NOT NULL, multiplier REAL NOT NULL, day TEXT NOT NULL,
 seconds INTEGER NOT NULL, manifest_path TEXT NOT NULL, manifest_sha256 TEXT NOT NULL,
 source_sha256 TEXT NOT NULL, plan_sha256 TEXT NOT NULL, generator TEXT NOT NULL,
 observer TEXT NOT NULL, bridge TEXT NOT NULL, score REAL NOT NULL,
 packets INTEGER NOT NULL, excess_ms REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS timer_windows (
 trial_id TEXT NOT NULL REFERENCES trials(id), window_index INTEGER NOT NULL,
 start_ns TEXT NOT NULL, packet_count INTEGER NOT NULL,
 PRIMARY KEY(trial_id,window_index)
);
CREATE TABLE IF NOT EXISTS timer_models (
 id TEXT PRIMARY KEY, algorithm TEXT NOT NULL, created TEXT NOT NULL,
 scope TEXT NOT NULL, alpha REAL NOT NULL, reference_count INTEGER NOT NULL,
 minimum_tail REAL NOT NULL, active INTEGER NOT NULL DEFAULT 0,
 source_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS timer_reference (
 model_id TEXT NOT NULL REFERENCES timer_models(id), seed INTEGER NOT NULL,
 score REAL NOT NULL, PRIMARY KEY(model_id,seed)
);
CREATE TABLE IF NOT EXISTS timer_evaluation (
 model_id TEXT NOT NULL REFERENCES timer_models(id), trial_id TEXT NOT NULL REFERENCES trials(id),
 tail_p REAL NOT NULL, reference_count INTEGER NOT NULL, eligible INTEGER NOT NULL,
 PRIMARY KEY(model_id,trial_id)
);
CREATE TABLE IF NOT EXISTS timer_decisions (
 id TEXT PRIMARY KEY, created_ms INTEGER NOT NULL, player_uuid TEXT NOT NULL,
 session_id TEXT NOT NULL, model_id TEXT NOT NULL, score REAL NOT NULL,
 tail_p REAL NOT NULL, alpha_spent REAL NOT NULL, reference_count INTEGER NOT NULL,
 episode INTEGER NOT NULL, excess_ms REAL NOT NULL,
 eligible INTEGER NOT NULL, action TEXT NOT NULL, reason TEXT NOT NULL, evidence_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS timer_decisions_player ON timer_decisions(player_uuid,created_ms);
