package dev.fox.anticheat.report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/** Frozen reference at startup; bounded asynchronous SQLite writes before punishment. */
public final class TimerStore implements Consumer<String>, AutoCloseable {
    public static final class Assessment {
        public final String id, player, session, model, reason;
        public final double score, tail, spent, excess;
        public final int references;
        public final long episode;
        public final boolean eligible;
        Assessment(String id, FindingRecord f) {
            this.id = id;
            player = f.field("player");
            UUID.fromString(player);
            session = f.field("session");
            if (Long.parseLong(session) < 1)
                throw new IllegalArgumentException("session");
            model = f.evidence.get("model");
            reason = f.field("message");
            score = number(f, "score_pps");
            tail = number(f, "tail_p");
            spent = number(f, "alpha_spent");
            excess = number(f, "excess_ms");
            references = Integer.parseInt(f.evidence.get("reference_count"));
            episode = Long.parseLong(f.evidence.get("episode"));
            eligible = "true".equals(f.evidence.get("eligible"));
            if (tail <= 0 || tail > 1 || spent <= 0 || spent > .01 || references < 5 ||
                episode < 1 || score < 0)
                throw new IllegalArgumentException("Invalid Timer evidence");
        }
        Assessment(String id, FindingRecord f, boolean budget) {
            this.id = id;
            player = f.field("player");
            UUID.fromString(player);
            session = f.field("session");
            if (Long.parseLong(session) < 1) throw new IllegalArgumentException("session");
            model = "timer.budget.v1";
            reason = f.field("message");
            excess = number(f, "lead_ms");
            double elapsed = number(f, "elapsed_ms");
            double packets = number(f, "counted_packets");
            if (elapsed < 2000 || packets < 40 || excess < 250 ||
                number(f, "tick_cost_ms") != 50) throw new IllegalArgumentException("Invalid budget evidence");
            score = packets * 1000 / elapsed;
            tail = spent = Double.NaN; // A budget rule has no statistical probability.
            references = 0;
            episode = 0;
            eligible = true;
        }
        private static double number(FindingRecord f, String key) {
            double n = Double.parseDouble(f.evidence.get(key));
            if (!Double.isFinite(n))
                throw new IllegalArgumentException("Nonfinite Timer evidence");
            return n;
        }
    }

    private interface Work {
        void run(Connection connection) throws Exception;
    }
    private final ArrayBlockingQueue<Work> queue = new ArrayBlockingQueue<>(512);
    private final File file;
    private final Logger log;
    private final Consumer<Runnable> schedule;
    private final Function<Assessment, String> apply;
    private final boolean ban;
    private final boolean budgetBan;
    private final String run = UUID.randomUUID().toString();
    private String model;
    private double alpha;
    private final List<Double> reference = new ArrayList<>();
    private volatile boolean accepting = true, healthy = true;
    private final Thread worker;

    public TimerStore(File file, Logger log, boolean ban, Consumer<Runnable> schedule,
                      Function<Assessment, String> apply) throws Exception {
        this(file, log, ban, false, schedule, apply);
    }

    public TimerStore(File file, Logger log, boolean ban, boolean budgetBan,
                      Consumer<Runnable> schedule, Function<Assessment, String> apply) throws Exception {
        this.budgetBan = budgetBan;
        this.file = file;
        this.log = log;
        this.ban = ban;
        this.schedule = schedule;
        this.apply = apply;
        Class.forName("org.sqlite.JDBC");
        try (Connection db = open()) {
            try (InputStream in = TimerStore.class.getResourceAsStream("/timer-schema.sql")) {
                if (in == null)
                    throw new IOException("Missing Timer schema");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0)
                    bytes.write(buffer, 0, n);
                for (String sql :
                     new String(bytes.toByteArray(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.trim().isEmpty())
                        try (Statement s = db.createStatement()) {
                            s.execute(sql);
                        }
            }
            try (Statement s = db.createStatement();
                 ResultSet r = s.executeQuery("SELECT id,algorithm,scope,alpha,reference_count " +
                                              "FROM timer_models WHERE active=1")) {
                if (r.next()) {
                    model = r.getString(1);
                    String scope = r.getString(3);
                    alpha = r.getDouble(4);
                    int count = r.getInt(5);
                    if (!model.matches("[a-f0-9]{64}") ||
                        !"timer-episode-v1".equals(r.getString(2)) ||
                        !"scripted-local-1.8".equals(scope) || !Double.isFinite(alpha) ||
                        alpha <= 0 || alpha > .01)
                        throw new SQLException("Unsupported Timer model");
                    if (r.next())
                        throw new SQLException("Multiple active Timer models");
                    try (PreparedStatement p = db.prepareStatement(
                             "SELECT score FROM timer_reference WHERE model_id=? ORDER BY score")) {
                        p.setString(1, model);
                        try (ResultSet scores = p.executeQuery()) {
                            while (scores.next())
                                reference.add(scores.getDouble(1));
                        }
                    }
                    if (reference.size() != count || count < 5 || count > 4096)
                        throw new SQLException("Invalid reference count");
                    for (double value : reference)
                        if (!Double.isFinite(value) || value < 0 || value > 1000)
                            throw new SQLException("Invalid score");
                }
            }
        }
        worker = new Thread(this::write, "FoxAntiCheat-timer-db");
        worker.setDaemon(true);
        worker.start();
        log.info(model == null
                     ? "Timer baseline: none; no model enforcement"
                     : "Timer baseline: " + model.substring(0, 12) + " seeds=" + reference.size() +
                           " tail_floor=" + (1.0 / (reference.size() + 1)) +
                           " mode=" + (ban ? "ban" : "report"));
    }

    private Connection open() throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
        }
        return db;
    }

    public String nativeConfiguration() {
        if (model == null)
            return "";
        StringBuilder text =
            new StringBuilder("\ntimer.baseline.enabled=true\ntimer.baseline.model=")
                .append(model)
                .append("\ntimer.baseline.alpha=")
                .append(alpha)
                .append("\ntimer.baseline.reference=");
        for (int i = 0; i < reference.size(); i++) {
            if (i != 0)
                text.append(',');
            text.append(reference.get(i));
        }
        return text.append('\n').toString();
    }

    private boolean offer(Work work) {
        if (!accepting || !healthy)
            return false;
        if (queue.offer(work))
            return true;
        healthy = false;
        log.severe("Timer database queue full; enforcement disabled until restart");
        return false;
    }

    @Override
    public void accept(String json) {
        if (!accepting || !healthy)
            return;
        try {
            FindingRecord f = FindingRecord.read(json);
            if (budgetBan && f.field("check").equals("timer.budget.v1") &&
                f.field("level").equals("suspicious") &&
                f.field("message").equals("sustained_excess_client_tick_budget")) {
                acceptBudget(json, f);
                return;
            }
            if (model == null || !f.field("check").equals("timer.baseline.v1"))
                return;
            Assessment a =
                new Assessment(run + ":" + f.field("session") + ":" + f.field("event"), f);
            if (!model.equals(a.model) || a.references != reference.size())
                throw new IllegalArgumentException("Model mismatch");
            double expected = (1.0 + reference.stream().filter(v -> v >= a.score).count()) /
                              (reference.size() + 1);
            double spent = alpha / (a.episode * ((double)a.episode + 1));
            if (Math.abs(expected - a.tail) > 1e-12 || Math.abs(spent - a.spent) > 1e-12 ||
                a.eligible != (a.tail <= a.spent && a.score >= 20.5 && a.excess >= 1000))
                throw new IllegalArgumentException("Inconsistent native Timer evidence");
            offer(db -> {
                String action = a.eligible && ban ? "pending" : "report";
                try (PreparedStatement p =
                         db.prepareStatement("INSERT OR IGNORE INTO timer_decisions VALUES " +
                                             "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    int i = 1;
                    p.setString(i++, a.id);
                    p.setLong(i++, System.currentTimeMillis());
                    p.setString(i++, a.player);
                    p.setString(i++, a.session);
                    p.setString(i++, a.model);
                    p.setDouble(i++, a.score);
                    p.setDouble(i++, a.tail);
                    p.setDouble(i++, a.spent);
                    p.setInt(i++, a.references);
                    p.setLong(i++, a.episode);
                    p.setDouble(i++, a.excess);
                    p.setInt(i++, a.eligible ? 1 : 0);
                    p.setString(i++, action);
                    p.setString(i++, a.reason);
                    p.setString(i++, json);
                    if (p.executeUpdate() == 0)
                        return;
                }
                // Autocommit persists complete evidence before scheduling the main-thread action.
                if (action.equals("pending"))
                    schedule.accept(() -> {
                        String result = "skipped_store_unavailable";
                        if (accepting && healthy)
                            try {
                                result = apply.apply(a);
                            } catch (RuntimeException error) {
                                result = "action_failed";
                                log.severe(error.toString());
                            }
                        final String outcome = result;
                        offer(c -> {
                            try (PreparedStatement p = c.prepareStatement(
                                     "UPDATE timer_decisions SET action=? WHERE id=?")) {
                                p.setString(1, outcome);
                                p.setString(2, a.id);
                                p.executeUpdate();
                            }
                        });
                    });
            });
        } catch (RuntimeException error) {
            healthy = false;
            log.severe("Invalid Timer assessment; enforcement disabled: " + error.getMessage());
        }
    }

    private void acceptBudget(String json, FindingRecord f) {
        Assessment a = new Assessment(run + ":budget:" + f.field("session") + ":" + f.field("event"), f, true);
        offer(db -> {
            try (PreparedStatement p = db.prepareStatement(
                "INSERT OR IGNORE INTO timer_budget_decisions VALUES (?,?,?,?,?,?,?,?,?)")) {
                p.setString(1, a.id);
                p.setLong(2, System.currentTimeMillis());
                p.setString(3, a.player);
                p.setString(4, a.session);
                p.setDouble(5, a.excess);
                p.setDouble(6, a.score);
                p.setString(7, "pending");
                p.setString(8, a.reason);
                p.setString(9, json);
                if (p.executeUpdate() == 0) return;
            }
            // Same durable-before-action contract; no model probability is fabricated.
            schedule.accept(() -> {
                String result = "skipped_store_unavailable";
                if (accepting && healthy) {
                    try { result = apply.apply(a); }
                    catch (RuntimeException error) { result = "action_failed"; log.severe(error.toString()); }
                }
                final String outcome = result;
                offer(c -> {
                    try (PreparedStatement p = c.prepareStatement(
                        "UPDATE timer_budget_decisions SET action=? WHERE id=?")) {
                        p.setString(1, outcome);
                        p.setString(2, a.id);
                        p.executeUpdate();
                    }
                });
            });
        });
    }

    private void write() {
        try (Connection db = open()) {
            while (accepting || !queue.isEmpty()) {
                Work work = queue.poll(100, TimeUnit.MILLISECONDS);
                if (work != null && healthy)
                    work.run(db);
            }
        } catch (Exception error) {
            healthy = false;
            log.severe("Timer database failed; no further bans: " + error);
        }
    }

    @Override
    public void close() {
        accepting = false;
        try {
            worker.join(6000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
