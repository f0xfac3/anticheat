package dev.fox.anticheat.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Logger;

/**
 * Immutable portable linear models. Inference is bounded; all writes/actions occur off the packet
 * path.
 */
public final class BehaviorRuntime implements Consumer<BehaviorTelemetry.Window>, AutoCloseable {
    public static final class Decision {
        public final String id, player, session, detection, model, world, mode;
        public final double margin, tail;
        public final boolean candidate, enforce, outsideSupport;
        public final String evidence;
        Decision(String id, BehaviorTelemetry.Window w, Model m, double margin, double tail,
            boolean candidate, boolean outsideSupport, long look) {
            this.id = id;
            player = w.player;
            session = Long.toString(w.session);
            detection = m.detection;
            model = m.id;
            world = w.world;
            mode = m.mode;
            this.margin = margin;
            this.tail = tail;
            this.candidate = candidate;
            this.outsideSupport = outsideSupport;
            // Summable per-session error spending bounds repeated looks; never call tail a cheating
            // probability.
            double alpha = look > 0 ? m.tailCutoff / (look * (look + 1.0)) : 0;
            enforce = candidate && !outsideSupport && m.mode.equals("enforce") && tail <= alpha;
            String features = w.json();
            evidence = features.substring(0, features.length() - 1) + ",\"sequential_look\":" + look
                + ",\"alpha\":" + alpha + ",\"margin_threshold\":" + m.threshold
                + ",\"outside_support\":" + outsideSupport + ",\"world\":\""
                + world.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        }
    }
    private static final Set<String> FEATURES =
        new HashSet<>(Arrays.asList("movement_pps", "movement_cv", "attack_pps", "attack_cv",
            "attack_repeat", "ground_fraction", "turn_mean", "attacks"));
    private static final class Track {
        long epoch, segment, looks;
        final Deque<Double> margins = new ArrayDeque<>();
    }
    static final class Model {
        String id, detection, mode, digest;
        String[] features;
        double[] coefficients, references, minimum, maximum;
        double intercept, threshold, tailCutoff;
        int consecutive, minimumAttacks;
        final LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
        Model(File path) throws Exception {
            if (path.length() > 4_000_000)
                throw new IOException("Model file exceeds size bound");
            byte[] bytes = java.nio.file.Files.readAllBytes(path.toPath());
            StringBuilder hex = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            digest = hex.toString();
            Properties p = new Properties();
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                p.load(in);
            }
            id = p.getProperty("id");
            detection = p.getProperty("detection");
            mode = p.getProperty("mode");
            if (!"behavior-v1".equals(p.getProperty("feature_version"))
                || !id.matches("[a-f0-9]{64}") || !detection.matches("[a-z][a-z0-9_.-]{2,63}")
                || !Arrays.asList("shadow", "enforce", "disabled").contains(mode))
                throw new IOException("Invalid model identity");
            features = p.getProperty("features").split(",");
            coefficients = numbers(p.getProperty("coefficients"));
            references = numbers(p.getProperty("references"));
            minimum = numbers(p.getProperty("minimum"));
            maximum = numbers(p.getProperty("maximum"));
            if (features.length != coefficients.length || features.length > 8
                || references.length < 1 || references.length > 100000)
                throw new IOException("Model size");
            for (String feature : features)
                if (!FEATURES.contains(feature))
                    throw new IOException("Unknown feature");
            if (minimum.length != features.length || maximum.length != features.length)
                throw new IOException("Feature support bounds missing");
            for (int i = 0; i < features.length; i++)
                if (minimum[i] > maximum[i])
                    throw new IOException("Feature support bounds reversed");
            intercept = number(p.getProperty("intercept"));
            threshold = number(p.getProperty("threshold"));
            tailCutoff = number(p.getProperty("tail_cutoff"));
            consecutive = Integer.parseInt(p.getProperty("consecutive_windows"));
            minimumAttacks = Integer.parseInt(p.getProperty("minimum_attacks"));
            if (consecutive < 1 || consecutive > 20 || minimumAttacks < 0 || minimumAttacks > 10000
                || tailCutoff <= 0 || tailCutoff > .05)
                throw new IOException("Invalid policy");
            if (mode.equals("enforce") && 1.0 / (references.length + 1) > tailCutoff / 2)
                throw new IOException("Insufficient sequential reference resolution");
            Arrays.sort(references);
        }
        static double number(String s) {
            double d = Double.parseDouble(s);
            if (!Double.isFinite(d))
                throw new IllegalArgumentException("Nonfinite model");
            return d;
        }
        static double[] numbers(String s) {
            String[] parts = s.split(",");
            double[] a = new double[parts.length];
            for (int i = 0; i < a.length; i++)
                a[i] = number(parts[i]);
            return a;
        }
        Decision score(String run, BehaviorTelemetry.Window w) {
            if (mode.equals("disabled") || w.values.get("attacks") < minimumAttacks) {
                Track previous = tracks.get(w.player + ":" + w.session);
                if (previous != null)
                    previous.margins.clear();
                return null;
            }
            boolean outside = false;
            double margin = intercept;
            for (int i = 0; i < features.length; i++) {
                double value = w.values.get(features[i]);
                margin += coefficients[i] * value;
                if (value < minimum[i] || value > maximum[i])
                    outside = true;
            }
            if (!Double.isFinite(margin))
                return null;
            String key = w.player + ":" + w.session;
            Track t = tracks.get(key);
            if (t == null) {
                if (tracks.size() >= 4096)
                    tracks.remove(tracks.keySet().iterator().next());
                t = new Track();
                tracks.put(key, t);
            }
            if (t.segment != w.segment || w.epoch <= t.epoch || w.epoch - t.epoch > 45000)
                t.margins.clear();
            t.epoch = w.epoch;
            t.segment = w.segment;
            t.margins.addLast(margin);
            while (t.margins.size() > consecutive)
                t.margins.removeFirst();
            double sustained = Collections.min(t.margins);
            int low = 0, high = references.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (references[middle] < sustained)
                    low = middle + 1;
                else
                    high = middle;
            }
            int greater = references.length - low;
            double tail = (1.0 + greater) / (1 + references.length);
            boolean ready = t.margins.size() == consecutive;
            if (ready)
                t.looks++;
            Decision decision =
                new Decision(run + ":" + w.session + ":" + w.event + ":" + detection, w, this,
                    sustained, tail, ready && sustained > threshold && !outside, outside, t.looks);
            if (outside)
                t.margins.clear();
            return decision;
        }
    }
    private interface Work {
        void run(Connection db) throws Exception;
    }
    private final List<Model> models = new ArrayList<>();
    private final ArrayBlockingQueue<Work> queue = new ArrayBlockingQueue<>(512);
    private final File file;
    private final Logger log;
    private final Consumer<Runnable> schedule;
    private final Function<Decision, String> apply;
    private final Thread worker;
    private final String run = UUID.randomUUID().toString();
    private volatile boolean accepting = true, healthy = true;

    public BehaviorRuntime(File directory, Logger log, Consumer<Runnable> schedule,
        Function<Decision, String> apply) throws Exception {
        this.file = new File(directory, "analytics.sqlite");
        this.log = log;
        this.schedule = schedule;
        this.apply = apply;
        Class.forName("org.sqlite.JDBC");
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS behavior_windows(id TEXT PRIMARY KEY,created_ms "
                + "INTEGER NOT NULL,player_uuid TEXT NOT NULL,session_id TEXT NOT "
                + "NULL,features_json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS model_decisions(id TEXT PRIMARY KEY,created_ms "
                + "INTEGER NOT NULL,player_uuid TEXT NOT NULL,session_id TEXT NOT "
                + "NULL,detection_id TEXT NOT NULL,model_id TEXT NOT NULL,margin REAL NOT "
                + "NULL,tail_p REAL NOT NULL,flagged INTEGER NOT NULL,action TEXT NOT "
                + "NULL,evidence_json TEXT NOT NULL)");
        }
        File[] paths = new File(directory, "models").listFiles((d, n) -> n.endsWith(".properties"));
        if (paths != null && paths.length > 0) {
            Arrays.sort(paths);
            if (paths.length > 32)
                throw new IOException("At most 32 active models");
            try (Connection c = open(); PreparedStatement query = c.prepareStatement(
                                            "SELECT experiment_id,mode,artifact_sha256 FROM "
                                            + "deployments WHERE detection_id=?")) {
                for (File p : paths) {
                    Model model = new Model(p);
                    query.setString(1, model.detection);
                    try (ResultSet row = query.executeQuery()) {
                        if (!row.next() || !model.id.equals(row.getString(1))
                            || !model.mode.equals(row.getString(2))
                            || !model.digest.equals(row.getString(3)))
                            throw new IOException(
                                "Deployment file and registry disagree: " + model.detection);
                    }
                    models.add(model);
                }
            }
        }
        worker = new Thread(this::write, "FoxAntiCheat-behavior-db");
        worker.setDaemon(true);
        worker.start();
        log.info("Behavior telemetry ready | models=" + models.size() + " | windows=30s");
    }
    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
        }
        return c;
    }
    private boolean offer(Work work) {
        if (!accepting || !healthy)
            return false;
        if (queue.offer(work))
            return true;
        healthy = false;
        log.severe("Behavior queue full; model actions disabled");
        return false;
    }
    public void disable(String reason) {
        healthy = false;
        log.severe(reason + "; behavior actions disabled");
    }
    @Override
    public void accept(BehaviorTelemetry.Window w) {
        if (!accepting || !healthy)
            return;
        List<Decision> decisions = new ArrayList<>();
        for (Model m : models) {
            Decision d = m.score(run, w);
            if (d != null)
                decisions.add(d);
        }
        offer(c -> {
            try (PreparedStatement p = c.prepareStatement(
                     "INSERT OR IGNORE INTO behavior_windows VALUES(?,?,?,?,?)")) {
                p.setString(1, run + ":" + w.session + ":" + w.event);
                p.setLong(2, w.epoch);
                p.setString(3, w.player);
                p.setString(4, Long.toString(w.session));
                p.setString(5, w.json());
                p.executeUpdate();
            }
            for (Decision d : decisions) {
                String action = d.outsideSupport ? "outside_training_support"
                    : d.enforce                  ? "pending"
                    : d.candidate
                    ? (d.mode.equals("shadow") ? "shadow_candidate" : "below_response_cutoff")
                    : "observed";
                try (PreparedStatement p = c.prepareStatement(
                         "INSERT OR IGNORE INTO model_decisions VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1, d.id);
                    p.setLong(2, w.epoch);
                    p.setString(3, d.player);
                    p.setString(4, d.session);
                    p.setString(5, d.detection);
                    p.setString(6, d.model);
                    p.setDouble(7, d.margin);
                    p.setDouble(8, d.tail);
                    p.setInt(9, d.candidate ? 1 : 0);
                    p.setString(10, action);
                    p.setString(11, d.evidence);
                    if (p.executeUpdate() == 0)
                        continue;
                }
                if (d.enforce)
                    schedule.accept(() -> {
                        String result = "skipped_store_unavailable";
                        if (healthy && accepting)
                            try {
                                result = apply.apply(d);
                            } catch (RuntimeException e) {
                                result = "action_failed";
                                log.severe(e.toString());
                            }
                        final String outcome = result;
                        offer(db -> {
                            try (PreparedStatement p = db.prepareStatement(
                                     "UPDATE model_decisions SET action=? WHERE id=?")) {
                                p.setString(1, outcome);
                                p.setString(2, d.id);
                                p.executeUpdate();
                            }
                        });
                    });
            }
        });
    }
    private void write() {
        try (Connection c = open()) {
            while (accepting || !queue.isEmpty()) {
                Work w = queue.poll(100, TimeUnit.MILLISECONDS);
                if (w != null && healthy)
                    w.run(c);
            }
        } catch (Exception e) {
            healthy = false;
            log.severe("Behavior persistence failed; model actions disabled: " + e);
        }
    }
    @Override
    public void close() {
        accepting = false;
        try {
            worker.join(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
