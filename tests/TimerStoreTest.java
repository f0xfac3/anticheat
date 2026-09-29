package dev.fox.anticheat.report;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Disposable databases only; tests the durable-before-action contract. */
public final class TimerStoreTest {
    private static final String MODEL = String.join("", java.util.Collections.nCopies(64, "a"));
    private static void require(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
    private static File database(int count) throws Exception {
        File file = Files.createTempFile("timer-store-", ".sqlite").toFile();
        file.deleteOnExit();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            String schema = new String(
                Files.readAllBytes(Paths.get("plugin/src/main/resources/timer-schema.sql")),
                StandardCharsets.UTF_8);
            for (String sql : schema.split(";"))
                if (!sql.trim().isEmpty())
                    try (Statement s = c.createStatement()) {
                        s.execute(sql);
                    }
            try (PreparedStatement p = c.prepareStatement(
                     "INSERT INTO timer_models VALUES (?, " +
                     "'timer-episode-v1','test','scripted-local-1.8',.001,?,?,1,'{}')")) {
                p.setString(1, MODEL);
                p.setInt(2, count);
                p.setDouble(3, 1.0 / (count + 1));
                p.executeUpdate();
            }
            c.setAutoCommit(false);
            try (PreparedStatement p =
                     c.prepareStatement("INSERT INTO timer_reference VALUES (?,?,20)")) {
                for (int i = 0; i < count; i++) {
                    p.setString(1, MODEL);
                    p.setInt(2, i);
                    p.executeUpdate();
                }
                c.commit();
            }
        }
        return file;
    }
    private static String finding(int count, boolean eligible, String model) {
        return "{\"level\":\"assessment\",\"check\":\"timer.baseline.v1\",\"player\":\"00000000-" +
               "0000-0000-0000-000000000001\","
            +
            "\"session\":\"1\",\"event\":\"100\",\"message\":\"test\",\"evidence\":{\"model\":\"" +
            model + "\","
            + "\"score_pps\":\"21.4\",\"tail_p\":\"" + (1.0 / (count + 1)) +
            "\",\"alpha_spent\":\"0.0005\","
            + "\"reference_count\":\"" + count +
            "\",\"episode\":\"1\",\"excess_ms\":\"11900\",\"eligible\":\"" + eligible + "\"}}";
    }
    private static int rows(File file, String action) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             PreparedStatement p =
                 c.prepareStatement("SELECT COUNT(*) FROM timer_decisions WHERE action=?")) {
            p.setString(1, action);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Logger log = Logger.getLogger("timer-test");
        File calibrated = database(1999);
        BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        AtomicInteger actions = new AtomicInteger();
        try (TimerStore store = new TimerStore(calibrated, log, true, tasks::add, a -> {
                 try {
                     require(rows(calibrated, "pending") == 1, "action before durable evidence");
                 } catch (Exception e) {
                     throw new RuntimeException(e);
                 }
                 actions.incrementAndGet();
                 return "banned";
             })) {
            require(store.nativeConfiguration().contains("timer.baseline.reference=20.0"),
                    "reference not exported");
            String event = finding(1999, true, MODEL);
            store.accept(event);
            store.accept(event);
            Runnable task = tasks.poll(5, TimeUnit.SECONDS);
            require(task != null, "action missing");
            task.run();
            require(tasks.poll(200, TimeUnit.MILLISECONDS) == null, "duplicate action");
        }
        require(actions.get() == 1 && rows(calibrated, "banned") == 1, "ban result not persisted");
        File small = database(9);
        try (TimerStore store = new TimerStore(small, log, true, tasks::add, a -> {
                 throw new AssertionError("insufficient baseline acted");
             })) {
            store.accept(finding(9, false, MODEL));
        }
        require(rows(small, "report") == 1 && tasks.isEmpty(), "abstention missing");
        File wrong = database(1999);
        try (TimerStore store = new TimerStore(wrong, log, true, tasks::add, a -> {
                 throw new AssertionError("model mismatch acted");
             })) {
            store.accept(
                finding(1999, true, String.join("", java.util.Collections.nCopies(64, "b"))));
        }
        require(tasks.isEmpty() && rows(wrong, "pending") == 0, "mismatch not rejected");
        File report = database(1999);
        try (TimerStore store = new TimerStore(report, log, false, tasks::add, a -> {
                 throw new AssertionError("report mode acted");
             })) {
            store.accept(finding(1999, true, MODEL));
        }
        require(rows(report, "report") == 1 && tasks.isEmpty(), "report mode failed");
        System.out.println("PASS Timer SQLite: durable evidence, one action, small baseline " +
                           "abstention, mismatched model, report mode");
    }
}
