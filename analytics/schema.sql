PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS detections (
 id TEXT PRIMARY KEY, version INTEGER NOT NULL, name TEXT NOT NULL,
 spec_json TEXT NOT NULL, source_hash TEXT NOT NULL, created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS samples (
 id TEXT PRIMARY KEY, manifest_path TEXT NOT NULL, source_hash TEXT NOT NULL,
 player_group TEXT NOT NULL, day TEXT NOT NULL, client TEXT NOT NULL,
 network TEXT NOT NULL, input_source TEXT NOT NULL, script_family TEXT NOT NULL,
 behavior TEXT NOT NULL, label TEXT NOT NULL, route TEXT NOT NULL,
 review TEXT NOT NULL, reviewer TEXT NOT NULL, purpose TEXT NOT NULL,
 metadata_json TEXT NOT NULL, created_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sample_windows (
 sample_id TEXT NOT NULL REFERENCES samples(id), ordinal INTEGER NOT NULL,
 features_json TEXT NOT NULL, PRIMARY KEY(sample_id,ordinal)
);
CREATE TABLE IF NOT EXISTS experiments (
 id TEXT PRIMARY KEY, detection_id TEXT NOT NULL REFERENCES detections(id),
 created_ms INTEGER NOT NULL, scope TEXT NOT NULL, report_json TEXT NOT NULL,
 model_json TEXT NOT NULL, status TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS deployments (
 detection_id TEXT PRIMARY KEY REFERENCES detections(id), experiment_id TEXT NOT NULL REFERENCES experiments(id),
 mode TEXT NOT NULL, previous_id TEXT, activated_ms INTEGER NOT NULL, artifact_sha256 TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT, created_ms INTEGER NOT NULL,
 operation TEXT NOT NULL, subject TEXT NOT NULL, detail_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS behavior_windows (
 id TEXT PRIMARY KEY, created_ms INTEGER NOT NULL, player_uuid TEXT NOT NULL,
 session_id TEXT NOT NULL, features_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS behavior_time ON behavior_windows(created_ms);
CREATE TABLE IF NOT EXISTS model_decisions (
 id TEXT PRIMARY KEY, created_ms INTEGER NOT NULL, player_uuid TEXT NOT NULL,
 session_id TEXT NOT NULL, detection_id TEXT NOT NULL, model_id TEXT NOT NULL,
 margin REAL NOT NULL, tail_p REAL NOT NULL, flagged INTEGER NOT NULL,
 action TEXT NOT NULL, evidence_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS model_time ON model_decisions(created_ms);
CREATE TABLE IF NOT EXISTS collection_jobs (
 id TEXT PRIMARY KEY, player_name TEXT NOT NULL, created_ms INTEGER NOT NULL,
 metadata_json TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL
);
