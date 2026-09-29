package dev.fox.monitor;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Read-only snapshots, loaded off the Swing thread. SQLite stays the source of truth. */
final class Registry implements AutoCloseable {
    static final class Snapshot {
        final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        final long loaded = System.currentTimeMillis();
        String error = "";
        List<Map<String, Object>> rows(String key) {
            return tables.getOrDefault(key, Collections.emptyList());
        }
    }
    private final MonitorConfig config;
    private URLClassLoader loader;
    private Driver driver;
    Registry(MonitorConfig config) {
        this.config = config;
    }
    Snapshot read() {
        Snapshot result = new Snapshot();
        if (!Files.isRegularFile(config.registry)) {
            result.error = "Registry not initialized";
            return result;
        }
        try {
            if (driver == null) {
                loader = new URLClassLoader(new java.net.URL[] {config.serverJar.toUri().toURL()},
                    Registry.class.getClassLoader());
                driver = (Driver) Class.forName("org.sqlite.JDBC", true, loader)
                             .getDeclaredConstructor()
                             .newInstance();
            }
            Properties properties = new Properties();
            properties.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
            properties.setProperty("busy_timeout", "1000");
            try (Connection db = driver.connect("jdbc:sqlite:" + config.registry, properties)) {
                query(result, db, "detections",
                    "SELECT d.*,p.mode,p.experiment_id FROM detections d LEFT JOIN deployments p "
                    + "ON p.detection_id=d.id ORDER BY d.name LIMIT 100");
                query(result, db, "experiments",
                    "SELECT * FROM experiments ORDER BY created_ms DESC LIMIT 100");
                query(result, db, "samples",
                    "SELECT * FROM samples ORDER BY created_ms DESC LIMIT 500");
                query(result, db, "jobs",
                    "SELECT * FROM collection_jobs ORDER BY created_ms DESC LIMIT 50");
                query(result, db, "windows",
                    "SELECT * FROM behavior_windows WHERE created_ms>"
                        + (System.currentTimeMillis() - 3600000)
                        + " ORDER BY created_ms DESC LIMIT 2000");
                query(result, db, "decisions",
                    "SELECT * FROM model_decisions WHERE created_ms>"
                        + (System.currentTimeMillis() - 3600000)
                        + " ORDER BY created_ms DESC LIMIT 2000");
                query(result, db, "incidents",
                    "SELECT * FROM model_decisions WHERE flagged=1 ORDER BY created_ms DESC LIMIT "
                    + "500");
                query(result, db, "coverage",
                    "SELECT input_source,review,COUNT(*) AS count FROM samples GROUP BY "
                    + "input_source,review");
                query(result, db, "audit", "SELECT * FROM audit ORDER BY id DESC LIMIT 100");
                query(result, db, "totals",
                    "SELECT (SELECT COUNT(*) FROM model_decisions WHERE action='banned') AS "
                    + "bans,(SELECT COUNT(*) FROM model_decisions WHERE flagged=1 AND created_ms>"
                        + (System.currentTimeMillis() - 3600000) + ") AS candidates");
            }
            Path timer = config.server.resolve("plugins/FoxAntiCheat/anticheat.sqlite");
            if (Files.isRegularFile(timer))
                try (Connection db = driver.connect("jdbc:sqlite:" + timer, properties)) {
                    query(result, db, "native_incidents",
                        "SELECT id,created_ms,player_uuid,session_id,'timer.budget.v1' AS "
                        + "detection_id,action,evidence_json FROM timer_budget_decisions ORDER BY "
                        + "created_ms DESC LIMIT 200");
                    query(result, db, "native_totals",
                        "SELECT (SELECT COUNT(*) FROM timer_budget_decisions WHERE "
                        + "action='banned')+(SELECT COUNT(*) FROM timer_decisions WHERE "
                        + "action='banned') AS bans");
                }
        } catch (Exception error) {
            result.error = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        return result;
    }
    private void query(Snapshot s, Connection db, String name, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = db.createStatement();
            ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++)
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                rows.add(Collections.unmodifiableMap(row));
            }
        }
        s.tables.put(name, Collections.unmodifiableList(rows));
    }
    public void close() {
        if (loader != null)
            try {
                loader.close();
            } catch (Exception ignored) {
            }
    }
}
