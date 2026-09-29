/**
 * MonitorTest.java tests log parsing, model limits, file rotation, and subprocess I/O.
 * It does not start Minecraft or evaluate detector accuracy.
 */

package dev.fox.monitor;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class MonitorTest{
    private static int passed;

    interface Task{
        void run() throws Exception;
    }

    static String json(String level, String event){
        return "{\"level\":\"" + level + "\",\"check\":\"fastbreak.request.v1\","
            + "\"player\":\"7ec6b679-2958-4a61-9588-d2703764ee77\",\"session\":\"1\","
            + "\"event\":\"" + event + "\",\"observed_ns\":\"18446744073709551615\","
            + "\"epoch_ms\":\"1789841024852\",\"message\":\"repeated_early_completion_requests\","
            + "\"evidence\":{\"observed_ms\":\"251.015\",\"expected_ms\":\"900.000\","
            + "\"server_break_outcome\":\"NOT_MEASURED\",\"samples\":\"3\"}}";
    }

    static void require(boolean condition, String message){
        if(!condition)
            throw new AssertionError(message);
    }

    private static void test(String name, Task task) throws Exception{
        task.run();
        ++passed;
        System.out.println("PASS " + name);
    }

    private static boolean rejected(String text){
        try{
            Finding.read(text);
            return false;
        }catch(IllegalArgumentException expected){
            return true;
        }
    }

    static void append(Path file, String text) throws Exception{
        Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static void main(String[] arguments) throws Exception{
        test("Timer response timeline distinguishes findings from completed bans", ()->{
            MonitorModel m = new MonitorModel();
            m.log("[09:24:00 INFO]: UUID of player elleliska is cafb67ce-e238-37f9-accc-b444f4c02443");
            m.log("[09:24:00 INFO]: [FoxAntiCheat] C++ engine loaded. Spigot 1.8.8");
            require(m.engineState.equals("Initialized"), "new startup message not recognized");
            m.log("[09:24:01 WARN]: [FoxAntiCheat] SUSPICIOUS | elleliska | timer.budget.v1 | sustained_excess_client_tick_budget");
            require(m.responses.size()==1 && m.bans==0, "detection is not a ban");
            String ban="[09:24:02 WARN]: [FoxAntiCheat] BAN | elleliska | timer.budget.v1 | evidence=run:budget:1:2997";
            m.log(ban); m.log(ban);
            require(m.responses.size()==2 && m.bans==1, "duplicate ban counted");
            require(m.responses.peekFirst()[3].equals("run:budget:1:2997"), "evidence ID lost");
            require(m.players.get("cafb67ce-e238-37f9-accc-b444f4c02443").status.equals("Banned"), "player response missing");
            m.log("[09:24:03 INFO]: [FoxAntiCheat] Timer budget enforcement: true | world=ac_collection_lab");
            require(m.policy.contains("true"), "policy missing");
        });

        test("native Finding fields parsed without numeric conversion", ()->{
            Finding finding = Finding.read(json("suspicious", "329"));
            require(finding.field("event").equals("329"), "ordinal");
            require(finding.field("observed_ns").equals("18446744073709551615"), "uint64 rounded");
            require(finding.evidence.get("samples").equals("3"), "evidence");
        });
        test("display summarizes measurements without changing verdict", ()->{
            Finding finding = Finding.read(json("suspicious", "329"));
            require(finding.suspicious(), "level lost");
            require(finding.summary().equals("251 ms observed / 900 ms expected"), "summary");
            require(finding.evidence.get("observed_ms").equals("251.015"), "evidence modified");
        });
        test("Reach measurements display without implying a reconstructed client view", ()->{
            String record = json("suspicious", "1")
                .replace("fastbreak.request.v1", "reach.stationary.v1")
                .replace("observed_ms", "old_observed_ms")
                .replace("\"samples\":\"3\"", "\"minimum_distance\":\"3.369\",\"allowed_distance\":\"3.050\",\"samples\":\"3\"");
            Finding finding = Finding.read(record);
            require(finding.detector().equals("Reach"), "Reach name");
            require(finding.summary().contains("3.37") || finding.summary().contains("3.369"), "Reach measurement");
        });
        test("Autoclicker cadence display retains the native verdict", ()->{
            String record = json("trace", "2")
                .replace("fastbreak.request.v1", "autoclicker.cadence.v1")
                .replace("observed_ms", "old_observed_ms")
                .replace("\"samples\":\"3\"", "\"attack_cps\":\"13.245\",\"cv\":\"0.010\",\"samples\":\"3\"");
            Finding finding = Finding.read(record);
            require(finding.detector().equals("Autoclicker"), "cadence name");
            require(finding.summary().contains("13.2"), "cadence measurement");
            require(!finding.suspicious(), "display promoted trace");
        });
        test("escaped text and unicode decoded", ()->{
            Finding finding = Finding.read(json("trace", "1").replace("repeated_early_completion_requests", "quote\\\"\\n\\u0041"));
            require(finding.field("message").equals("quote\"\nA"), "escaping");
        });
        test("invalid native JSON rejected", ()->{
            require(rejected("{}"), "missing fields");
            require(rejected(json("trace", "1") + " extra"), "trailing");
            require(rejected(json("trace", "1").replace("\"level\":\"trace\"", "\"level\":42")), "wrong schema");
            require(rejected(json("trace", "1").replace("\"message\":", "\"level\":\"trace\",\"message\":")), "duplicate");
            require(rejected(json("trace", "1").replace("samples", "\\q")), "escape");
        });
        test("trace findings do not become alerts", ()->{
            MonitorModel model = new MonitorModel();
            model.finding(json("trace", "1"), "test", null);
            require(model.received == 1 && model.suspicious == 0 && model.alerts.isEmpty(), "trace promoted");
        });
        test("unknown check and level remain in activity", ()->{
            MonitorModel model = new MonitorModel();
            model.finding(json("new-level", "1").replace("fastbreak.request.v1", "new.check"), "test", null);
            require(model.activity.size() == 1 && model.alerts.isEmpty(), "future finding dropped/promoted");
        });
        test("console and evidence mirrors count only once", ()->{
            MonitorModel model = new MonitorModel();
            String json = json("suspicious", "329");
            model.log("[14:03:44 WARN]: [FoxAntiCheat] " + json);
            model.finding(json, "file", null);
            require(model.received == 1 && model.suspicious == 1, "duplicate alert");
            require(model.alerts.peek().time().equals("14:03:44"), "console time");
        });
        test("chat text cannot impersonate a native Finding log", ()->{
            MonitorModel model = new MonitorModel();
            model.log("[14:03:44 INFO]: <player> [FoxAntiCheat] " + json("suspicious", "1"));
            require(model.received == 0, "chat forged finding");
        });
        test("player UUID mapping displayed as a name", ()->{
            MonitorModel model = new MonitorModel();
            model.log("[14:03:30 INFO]: UUID of player evilgirlscout is 7ec6b679-2958-4a61-9588-d2703764ee77");
            model.finding(json("suspicious", "1"), "file", null);
            require(model.playerName(model.alerts.peek()).equals("evilgirlscout"), "name mapping");
            require(model.flaggedPlayers() == 1, "player count");
        });
        test("chat cannot change the player name map", ()->{
            MonitorModel model = new MonitorModel();
            model.log("[14:03:30 INFO]: <attacker> UUID of player fake is 7ec6b679-2958-4a61-9588-d2703764ee77");
            require(model.names.isEmpty(), "chat spoofed UUID lookup");
        });
        test("zero alerts is not displayed as a clean verdict", ()->{
            MonitorModel model = new MonitorModel();
            model.finding(json("trace", "1"), "file", null);
            require(model.players.values().iterator().next().status.equals("Observed"), "invented clean verdict");
        });
        test("engine readiness requires its initialization message", ()->{
            MonitorModel model = new MonitorModel();
            model.log("[12:00:00 INFO]: Done (1s)! For help, type \"help\"");
            require(model.ready && model.engineState.equals("Waiting"), "server ready implies engine ready");
            model.log("[12:00:00 INFO]: [FoxAntiCheat] C++ detection engine loaded. Java adapter: Spigot 1.8.8.");
            require(model.engineState.equals("Initialized"), "plugin ready");
        });
        test("bounded activity and alert retention preserves session counters", ()->{
            MonitorModel model = new MonitorModel();

            for(int i = 0; i < 6200; ++i)
                model.finding(json("suspicious", Integer.toString(i)), "test", null);

            require(model.activity.size() <= 5000 && model.alerts.size() <= 2000, "unbounded model");
            require(model.suspicious == 6200, "counter reset after eviction");
        });
        test("filter finds player, check, and evidence", ()->{
            MonitorModel model = new MonitorModel();
            model.finding(json("suspicious", "1"), "file", null);
            require(model.matching(true, "NOT_MEASURED").size() == 1, "evidence filter");
            require(model.matching(true, "absent").isEmpty(), "filter");
        });
        test("split UTF-8 lines assembled including CRLF", ()->{
            List<String> lines = new ArrayList<>();
            LineReader reader = new LineReader(lines::add);
            byte[] bytes = "alpha\r\n\u00e9\nlast".getBytes(StandardCharsets.UTF_8);

            for(byte value : bytes)
                reader.accept(new byte[]{value}, 1);

            reader.finish();
            require(lines.equals(Arrays.asList("alpha", "\u00e9", "last")), "split line decoder");
        });
        test("oversized log line is bounded and identified", ()->{
            List<String> lines = new ArrayList<>();
            LineReader reader = new LineReader(lines::add);
            byte[] bytes = new byte[80000];
            Arrays.fill(bytes, (byte)'x');
            reader.accept(bytes, bytes.length);
            reader.finish();
            require(lines.get(0).contains("Oversized"), "unbounded line");
        });
        test("evidence tail excludes pre-existing history", ()->{
            Path dir = Files.createTempDirectory("monitor-tail");
            Path file = dir.resolve("findings-0.jsonl");
            append(file, json("suspicious", "old") + "\n");
            List<String> records = new ArrayList<>();
            EvidenceTail tail = new EvidenceTail(dir, records::add, message->{});
            tail.prime();
            tail.poll();
            append(file, json("suspicious", "new") + "\n");
            tail.poll();
            tail.poll();
            require(records.size() == 1 && records.get(0).contains("\"new\""), "old or duplicate record");
        });
        test("evidence tail preserves partial writes", ()->{
            Path dir = Files.createTempDirectory("monitor-tail-partial");
            Path file = dir.resolve("findings-0.jsonl");
            List<String> records = new ArrayList<>();
            EvidenceTail tail = new EvidenceTail(dir, records::add, message->{});
            tail.prime();
            String text = json("trace", "partial");
            append(file, text.substring(0, 20));
            tail.poll();
            require(records.isEmpty(), "partial parsed");
            append(file, text.substring(20) + "\n");
            tail.poll();
            require(records.equals(Arrays.asList(text)), "partial lost");
        });
        test("evidence rotation preserves unread old and new file records", ()->{
            Path dir = Files.createTempDirectory("monitor-tail-rotate");
            Path file = dir.resolve("findings-0.jsonl");
            append(file, json("trace", "old") + "\n");
            List<String> records = new ArrayList<>();
            EvidenceTail tail = new EvidenceTail(dir, records::add, message->{});
            tail.prime();
            append(file, json("trace", "unread") + "\n");
            Files.move(file, dir.resolve("findings-1.jsonl"));
            append(file, json("trace", "new") + "\n");
            tail.poll();
            tail.poll();
            require(records.size() == 2, "rotation missing/duplicate");
        });
        test("missing evidence folder may be created after server startup", ()->{
            Path dir = Files.createTempDirectory("monitor-late").resolve("logs");
            List<String> records = new ArrayList<>();
            EvidenceTail tail = new EvidenceTail(dir, records::add, message->{});
            tail.prime();
            Files.createDirectories(dir);
            append(dir.resolve("findings-0.jsonl"), json("suspicious", "1") + "\n");
            tail.poll();
            require(records.size() == 1, "late file missed");
        });
        test("server configuration resolves paths relative to monitor", ()->{
            Path dir = Files.createTempDirectory("monitor-config");
            MonitorConfig config = new MonitorConfig(dir);
            require(config.server.equals(dir.resolve("../../server-1.8").toAbsolutePath().normalize()), "wrong cwd");
            require(config.maximumHeap.equals("2G"), "heap default");
        });
        test("invalid JVM heap arguments rejected", ()->{
            Path dir = Files.createTempDirectory("monitor-config-bad");
            append(dir.resolve("monitor.properties"), "heap.maximum=2G -jar other.jar\n");
            boolean rejected = false;

            try{ new MonitorConfig(dir); }catch(IllegalArgumentException expected){ rejected = true; }

            require(rejected, "argument injection");
        });
        test("already listening server port detected without sending game data", ()->{
            try(ServerSocket socket = new ServerSocket(0)){
                require(ServerProcess.portOpen(socket.getLocalPort()), "port check");
            }
        });
        test("real subprocess captures both streams and sends stop over stdin", ()->{
            List<String> lines = Collections.synchronizedList(new ArrayList<>());
            List<String> notices = Collections.synchronizedList(new ArrayList<>());
            ServerProcess process = new ServerProcess(lines::add, notices::add);
            Path directory = Files.createTempDirectory("monitor server with spaces ");
            String java = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classes = Paths.get(MonitorTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(()->{
                try{
                    process.runCommand(Arrays.asList(java, "-cp", classes, "dev.fox.monitor.FakeServer"), directory, directory.resolve("logs"));
                }catch(Throwable failure){ error.set(failure); }
            });
            worker.start();

            for(int i = 0; i < 100 && !lines.contains("[12:00:00 WARN]: stderr captured"); ++i)
                Thread.sleep(30);

            require(process.alive(), "subprocess not running: " + error.get());
            process.command("list");

            for(int i = 0; i < 100 && !lines.contains("command=list"); ++i)
                Thread.sleep(20);

            process.stop();
            worker.join(5000);

            if(worker.isAlive()){
                process.forceStop();
                throw new AssertionError("stop command failed");
            }

            require(error.get() == null, "subprocess error: " + error.get());
            String archive = new String(Files.readAllBytes(process.capture), StandardCharsets.UTF_8);
            require(archive.contains("stderr captured") && archive.contains("Saved and stopped"), "streams not archived");
            require(archive.contains("working-directory=" + directory), "working directory");
            require(archive.contains("command=list"), "command missing");
            require(process.state.equals("Stopped"), "exit status");
        });

        if(arguments.length > 0){
            test("uploaded gameplay log replay", ()->{
                MonitorModel model = new MonitorModel();
                List<String> log = Files.readAllLines(Paths.get(arguments[0]), StandardCharsets.UTF_8);

                for(String line : log)
                    model.log(line);

                require(model.suspicious == 2, "expected exactly two suspicious findings");
                require(model.received == 42, "expected 42 structured records, found " + model.received);
                require(model.flaggedPlayers() == 1, "expected one player with alerts");
                require(model.alerts.peek().evidence.get("server_break_outcome").equals("NOT_MEASURED"), "outcome changed");
                System.out.println("  Replay: 42 findings, 2 suspicious, 1 player with alerts.");
            });
        }

        System.out.println(passed + " monitor tests passed");
    }
}
