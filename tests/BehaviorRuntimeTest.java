package dev.fox.anticheat.report;

import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.event.MovementContext;
import dev.fox.anticheat.event.MovementEvent;
import dev.fox.anticheat.packet.PacketInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Tests actual telemetry, exported coefficients, durable actions, and abstention boundaries. */
public final class BehaviorRuntimeTest {
    static void require(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
    static List<BehaviorTelemetry.Window> windows(double pps) {
        List<BehaviorTelemetry.Window> result = new ArrayList<>();
        BehaviorTelemetry telemetry = new BehaviorTelemetry(result::add);
        EventWriter writer = new EventWriter();
        telemetry.accept(writer.begin(1, 1, 1, 0, 0, 0)
                .session("12345678-1234-1234-1234-123456789abc", 47, 10808)
                .finish());
        MovementContext context = new MovementContext();
        context.world = "lab";
        context.available = true;
        for (int i = 1; i <= pps * 131; i++) {
            long ns = (long) (i * 1e9 / pps), epoch = ns / 1000000;
            PacketInfo packet = new PacketInfo(i, i, ns, epoch);
            MovementEvent movement = new MovementEvent(packet, 0, 5, 0, 0, 0, true, true, true);
            telemetry.accept(
                writer.begin(11, 1, i + 1, ns, epoch, 0).movement(movement, context, ns).finish());
        }
        return result;
    }
    static void model(Path folder, String mode, String references) throws Exception {
        Files.createDirectories(folder.resolve("models"));
        String text = "id=" + String.join("", Collections.nCopies(64, "a"))
            + "\ndetection=timer.test\nmode=" + mode
            + ("\nfeature_version=behavior-v1\nfeatures=movement_pps\ncoefficients=1\nintercept=-"
               + "20\nthreshold=0.5\nminimum=0\nmaximum=30\nminimum_attacks=0\nconsecutive_windows="
               + "3\ntail_cutoff=0.01\nreferences=")
            + references + "\n";
        Files.write(folder.resolve("models/timer.test.properties"),
            text.getBytes(StandardCharsets.US_ASCII));
        try (Connection db =
                 DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("analytics.sqlite"));
            Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS deployments(detection_id TEXT PRIMARY "
                              + "KEY,experiment_id TEXT,mode TEXT,artifact_sha256 TEXT)");
            StringBuilder digest = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(
                     text.getBytes(StandardCharsets.US_ASCII)))
                digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            try (PreparedStatement p =
                     db.prepareStatement("INSERT OR REPLACE INTO deployments VALUES(?,?,?,?)")) {
                p.setString(1, "timer.test");
                p.setString(2, String.join("", Collections.nCopies(64, "a")));
                p.setString(3, mode);
                p.setString(4, digest.toString());
                p.executeUpdate();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String refs = String.join(",", Collections.nCopies(200, "0"));
        List<BehaviorTelemetry.Window> fast = windows(21.4), normal = windows(20),
                                       extreme = windows(35);
        require(fast.size() == 4, "Four windows");
        Path folder = Files.createTempDirectory("behavior-runtime-test");
        model(folder, "enforce", refs);
        BehaviorRuntime.Model model =
            new BehaviorRuntime.Model(folder.resolve("models/timer.test.properties").toFile());
        require(!model.score("test", fast.get(0)).enforce, "Warmup cannot enforce");
        require(!model.score("test", fast.get(1)).enforce, "Two windows cannot enforce");
        BehaviorRuntime.Decision decision = model.score("test", fast.get(2));
        require(decision.candidate && decision.enforce, "First sustained look allowed");
        require(Math.abs(decision.margin - 1.4) < .05, "Folded linear coefficient parity");
        require(
            !model.score("test", fast.get(3)).enforce, "Later look spends smaller error budget");
        BehaviorRuntime.Model outside =
            new BehaviorRuntime.Model(folder.resolve("models/timer.test.properties").toFile());
        require(outside.score("test", extreme.get(0)).outsideSupport, "Unseen range abstains");
        BehaviorRuntime.Model clean =
            new BehaviorRuntime.Model(folder.resolve("models/timer.test.properties").toFile());
        for (BehaviorTelemetry.Window w : normal)
            require(!clean.score("test", w).candidate, "Normal cadence is not a candidate");
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        AtomicInteger actions = new AtomicInteger();
        try (BehaviorRuntime runtime = new BehaviorRuntime(
                 folder.toFile(), Logger.getAnonymousLogger(), callbacks::add, d -> {
                     try (Connection db = DriverManager.getConnection(
                              "jdbc:sqlite:" + folder.resolve("analytics.sqlite"));
                         PreparedStatement query =
                             db.prepareStatement("SELECT action FROM model_decisions WHERE id=?")) {
                         query.setString(1, d.id);
                         try (ResultSet row = query.executeQuery()) {
                             require(row.next() && row.getString(1).equals("pending"),
                                 "Durable evidence before action");
                         }
                     } catch (SQLException error) {
                         throw new AssertionError(error);
                     }
                     actions.incrementAndGet();
                     return "banned";
                 })) {
            for (BehaviorTelemetry.Window w : fast)
                runtime.accept(w);
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
            require(callback != null, "Expected callback");
            callback.run();
            require(callbacks.poll(250, TimeUnit.MILLISECONDS) == null,
                "No repeated enforcement without error budget");
        }
        require(actions.get() == 1, "Exactly one durable action");
        model(folder, "shadow", refs);
        try (BehaviorRuntime runtime =
                 new BehaviorRuntime(folder.toFile(), Logger.getAnonymousLogger(), callbacks::add,
                     d -> { throw new AssertionError("Shadow action"); })) {
            for (BehaviorTelemetry.Window w : fast)
                runtime.accept(w);
        }
        require(callbacks.isEmpty(), "Shadow never schedules a response");
        Files.write(folder.resolve("models/timer.test.properties"),
            "#modified".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.APPEND);
        try {
            new BehaviorRuntime(
                folder.toFile(), Logger.getAnonymousLogger(), callbacks::add, d -> "unexpected");
            throw new AssertionError("Modified deployment accepted");
        } catch (java.io.IOException expected) {
        }
        model(folder, "enforce", "0");
        try {
            new BehaviorRuntime.Model(folder.resolve("models/timer.test.properties").toFile());
            throw new AssertionError("Insufficient references accepted");
        } catch (java.io.IOException expected) {
        }
        System.out.println("PASS behavior scoring parity, warmup, sequential budget, support "
                           + "bounds, durable evidence, and shadow isolation");
    }
}
