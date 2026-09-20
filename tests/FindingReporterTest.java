/**
 * FindingReporterTest.java checks presentation without requiring Minecraft or JNI.
 */

package dev.fox.anticheat.report;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class FindingReporterTest{
    private static int passed;

    private static final String ALERT = "{\"level\":\"suspicious\","
        + "\"check\":\"fastbreak.request.v1\",\"player\":\"player-uuid\","
        + "\"session\":\"18446744073709551615\",\"event\":\"329\","
        + "\"message\":\"repeated_early_completion_requests\","
        + "\"evidence\":{\"observed_ms\":\"251.015\",\"expected_ms\":\"900.000\","
        + "\"sus_threshold_ms\":\"485.000\",\"samples\":\"3\","
        + "\"server_break_outcome\":\"NOT_MEASURED\"}}";

    private static void check(boolean condition, String name){
        if(!condition)
            throw new AssertionError(name);

        ++passed;
        System.out.println("PASS " + name);
    }

    private static final class Capture extends Handler{
        final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record){
            records.add(record);
        }

        @Override
        public void flush(){}

        @Override
        public void close(){}
    }

    private static Logger logger(Capture capture){
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        return logger;
    }

    private static boolean rejected(String json){
        try{
            FindingRecord.read(json);
            return false;
        }catch(IllegalArgumentException expected){
            return true;
        }
    }

    private static void parser(){
        FindingRecord finding = FindingRecord.read(ALERT);
        check(finding.field("session").equals("18446744073709551615"), "64-bit identifiers remain exact strings");
        check(finding.evidence.get("observed_ms").equals("251.015"), "evidence retains full precision");
        check(finding.evidence.get("server_break_outcome").equals("NOT_MEASURED"), "server outcome evidence is unchanged");
        check(finding.field("absent").isEmpty(), "missing optional fields have a safe fallback");

        String escaped = ALERT.replace(
            "\"samples\":\"3\"",
            "\"samples\":\"3\",\"test\":\"quote\\\" slash\\\\ tab\\t line\\n unicode\\u00e9\""
        );
        check(
            FindingRecord.read(escaped).evidence.get("test").equals("quote\" slash\\ tab\t line\n unicode\u00e9"),
            "JSON string escapes are decoded"
        );
        check(FindingRecord.read(" \t" + ALERT + " ").field("level").equals("suspicious"), "JSON whitespace is accepted");

        String[] invalid = {
            "", "{}", "[]", "null", ALERT + "junk", ALERT + "\n",
            ALERT.replace("\"level\":\"suspicious\"", "\"level\":null"),
            ALERT.replace("\"level\":\"suspicious\"", "\"level\":\"trace\",\"level\":\"suspicious\""),
            ALERT.replace("\"samples\":\"3\"", "\"samples\":\"3\",\"samples\":\"4\""),
            ALERT.replace("\"samples\":\"3\"", "\"samples\":{}"),
            ALERT.replace("\"samples\":\"3\"", "\"samples\":\"\\q\""),
            ALERT.replace("\"samples\":\"3\"", "\"samples\":\"\\u00zz\""),
            ALERT.replace("\"samples\":\"3\"", "\"samples\":\"3\","),
            ALERT.substring(0, ALERT.length() - 1) + ",\"evidence\":{}}"
        };
        boolean allRejected = true;

        for(String text : invalid){
            allRejected &= rejected(text);
        }

        check(allRejected, "invalid, duplicate, nested and unsupported fields are rejected");
        check(rejected(null), "null records are rejected");
        check(rejected(String.join("", Collections.nCopies(65537, " "))), "oversized records are rejected");

        boolean truncationRejected = true;

        for(int end = 0; end < ALERT.length(); ++end){
            truncationRejected &= rejected(ALERT.substring(0, end));
        }

        check(truncationRejected, "every truncation of a native-shaped record is rejected");
    }

    private static void reporter(Path root) throws Exception{
        Capture capture = new Capture();
        Logger logger = logger(capture);
        File directory = root.resolve("normal").toFile();
        FindingReporter reporter = new FindingReporter(logger, directory, uuid->"evilgirlscout");

        String trace = ALERT.replace("\"level\":\"suspicious\"", "\"level\":\"trace\"");
        reporter.accept(trace);
        check(capture.records.isEmpty(), "trace comparisons do not print console alerts");

        reporter.accept(ALERT);
        check(capture.records.size() == 1, "one native suspicious Finding produces one console alert");
        check(capture.records.get(0).getLevel() == Level.WARNING, "suspicious alerts use WARNING rather than INFO");
        check(
            capture.records.get(0).getMessage().equals(
                "SUSPICIOUS | evilgirlscout | FastBreak | 251 ms / 900 ms expected | samples=3"
            ),
            "FastBreak alert is compact and readable"
        );
        reporter.close();
        reporter.close();
        reporter.accept(ALERT);
        check(capture.records.size() == 1, "closing is idempotent and stops new output");

        List<String> saved = Files.readAllLines(
            directory.toPath().resolve("logs/findings-0.jsonl"),
            StandardCharsets.UTF_8
        );
        check(saved.equals(Arrays.asList(trace, ALERT)), "full trace and suspicious JSON are archived unchanged");

        try(FindingReporter second = new FindingReporter(logger, directory, uuid->"evilgirlscout")){
            second.accept(ALERT);
        }
        check(
            Files.readAllLines(directory.toPath().resolve("logs/findings-0.jsonl"), StandardCharsets.UTF_8).size() == 3,
            "archive appends across restarts"
        );

        Capture fallback = new Capture();

        try(FindingReporter output = new FindingReporter(logger(fallback), root.resolve("fallback").toFile(), uuid->null)){
            output.accept(ALERT);
            check(fallback.records.get(0).getMessage().contains("player-uuid"), "unknown player falls back to its identifier");
            output.accept(ALERT.replace("fastbreak.request.v1", "test.future.check"));
            check(fallback.records.get(1).getMessage().endsWith("test.future.check | repeated_early_completion_requests"), "new checks get a generic summary without detector changes");
            output.accept(ALERT.replace("\"251.015\"", "\"NaN\""));
            check(fallback.records.get(2).getMessage().contains("? ms"), "nonfinite display measurements do not invent a value");
            output.accept(ALERT.replace("\"251.015\"", "\"1e999999\""));
            check(fallback.records.get(3).getMessage().contains("? ms"), "oversized numeric values are handled safely");
            output.accept(ALERT.replace("\"suspicious\"", "\"future-level\""));
            check(fallback.records.get(4).getMessage().startsWith("FINDING |"), "unknown finding levels are not silently hidden");
            output.accept("bad-json");
            output.accept("still-bad");
            check(fallback.records.size() == 6 && fallback.records.get(5).getLevel() == Level.SEVERE, "schema errors are visible without repeated log spam");
            output.accept(ALERT);
            check(fallback.records.size() == 7, "bad display input does not disable subsequent alerts");
        }

        Capture safe = new Capture();

        try(FindingReporter output = new FindingReporter(logger(safe), root.resolve("safe").toFile(), uuid->"name\n\r\u001b\u00a7")){
            output.accept(ALERT);
            String line = safe.records.get(0).getMessage();
            check(!line.contains("\n") && !line.contains("\r") && !line.contains("\u001b") && !line.contains("\u00a7"), "console output contains no terminal or newline control characters");
        }

        File blocked = Files.createFile(root.resolve("not-a-directory")).toFile();
        Capture errors = new Capture();

        try(FindingReporter output = new FindingReporter(logger(errors), blocked, uuid->"player")){
            output.accept(ALERT);
            check(errors.records.size() == 2 && errors.records.get(0).getLevel() == Level.SEVERE
                && errors.records.get(1).getMessage().startsWith("SUSPICIOUS |"), "archive startup failure is reported without disabling console alerts");
        }
    }

    private static void queue() throws Exception{
        Capture warnings = new Capture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread main = Thread.currentThread();
        List<String> written = Collections.synchronizedList(new ArrayList<>());
        boolean[] wrongThread = {false};

        Handler slow = new Handler(){
            @Override
            public void publish(LogRecord record){
                wrongThread[0] |= Thread.currentThread() == main;
                entered.countDown();

                try{
                    if(!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("test writer timed out");
                }catch(InterruptedException error){
                    throw new AssertionError(error);
                }

                written.add(record.getMessage());
            }

            @Override
            public void flush(){}

            @Override
            public void close(){}
        };

        FindingLog log = new FindingLog(slow, logger(warnings), 2);
        log.offer("one");
        check(entered.await(2, TimeUnit.SECONDS), "evidence worker starts");
        log.offer("two");
        log.offer("three");
        log.offer("four");
        release.countDown();
        log.close();
        check(written.equals(Arrays.asList("one", "two", "three")), "full evidence queue drops rather than blocks or grows");
        check(!wrongThread[0], "record disk writes stay off the caller thread");
        check(warnings.records.size() == 1 && warnings.records.get(0).getMessage().contains("1 records not saved"), "dropped evidence is reported explicitly");
    }

    private static void replay(Path source, Path root) throws Exception{
        Capture capture = new Capture();
        List<String> inputs = new ArrayList<>();
        int suspicious = 0;

        for(String line : Files.readAllLines(source, StandardCharsets.UTF_8)){
            int start = line.indexOf("{\"level\":");

            if(start < 0)
                continue;

            String json = line.substring(start);
            inputs.add(json);

            if(FindingRecord.read(json).field("level").equals("suspicious"))
                ++suspicious;
        }

        Path directory = root.resolve("replay");

        try(FindingReporter reporter = new FindingReporter(logger(capture), directory.toFile(), uuid->"evilgirlscout")){
            for(String json : inputs){
                reporter.accept(json);
            }
        }

        check(capture.records.size() == suspicious, "uploaded log replay prints only the actual suspicious findings");
        check(
            Files.readAllLines(directory.resolve("logs/findings-0.jsonl"), StandardCharsets.UTF_8).equals(inputs),
            "uploaded log replay preserves every complete JSON record in the archive"
        );
        System.out.println("REPLAY " + inputs.size() + " records -> " + suspicious + " console alerts");

        for(LogRecord record : capture.records){
            System.out.println("[WARN] [FoxAntiCheat] " + record.getMessage());
        }
    }

    private static void combat(Path root) throws Exception{
        Capture capture = new Capture();
        try(FindingReporter reporter = new FindingReporter(logger(capture), root.resolve("combat").toFile(), uuid->"LabPlayer")){
            String reach = ALERT.replace("fastbreak.request.v1", "reach.stationary.v1")
                .replace("\"samples\":\"3\"", "\"minimum_distance\":\"3.369\",\"allowed_distance\":\"3.050\",\"samples\":\"3\"");
            reporter.accept(reach);
            check(capture.records.get(0).getMessage().contains("Reach | 3.37 blocks / 3.05 allowed"), "Reach summary uses measured lower bound");
            String cadence = ALERT.replace("fastbreak.request.v1", "autoclicker.cadence.v1")
                .replace("\"samples\":\"3\"", "\"attack_cps\":\"13.245\",\"samples\":\"3\"");
            reporter.accept(cadence);
            check(capture.records.get(1).getMessage().contains("Autoclicker | 13.25 attacks/s"), "cadence summary describes attack requests");
            reporter.accept(cadence.replace("\"suspicious\"", "\"trace\""));
            check(capture.records.size() == 2, "one cadence window is not promoted by reporter");
        }
    }

    public static void main(String[] arguments) throws Exception{
        Path root = Files.createTempDirectory("fox-report-tests-");

        try{
            parser();
            reporter(root);
            combat(root);
            queue();

            if(arguments.length != 0)
                replay(new File(arguments[0]).toPath(), root);

            System.out.println(passed + " reporting checks passed");
        }finally{
            try(java.util.stream.Stream<Path> paths = Files.walk(root)){
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{
                    try{
                        Files.deleteIfExists(path);
                    }catch(IOException error){
                        throw new UncheckedIOException(error);
                    }
                });
            }
        }
    }
}
