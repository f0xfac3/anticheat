/**
 * FindingReporter.java prints compact suspicious alerts and keeps full JSON
 * evidence out of the server console. It does not make detection decisions.
 */

package dev.fox.anticheat.report;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

public final class FindingReporter implements Consumer<String>, AutoCloseable{
    private final Logger logger;
    private final Function<String, String> playerName;
    private final FindingLog details;
    private boolean invalidReported;
    private boolean closed;

    public FindingReporter(
        Logger logger,
        File dataDirectory,
        Function<String, String> playerName
    ){
        this.logger = logger;
        this.playerName = playerName;

        FindingLog log = null;

        try{
            log = new FindingLog(new File(dataDirectory, "logs"), logger);
        }catch(IOException | RuntimeException error){
            logger.severe("Cannot open evidence files; console alerts remain active: " + clean(error.toString(), 160));
        }

        details = log;
    }

    // Every returned Finding goes to the evidence file; only suspicious ones go to the console.
    @Override
    public void accept(String json){
        if(closed)
            return;

        FindingRecord finding;

        try{
            finding = FindingRecord.read(json);
        }catch(IllegalArgumentException error){
            if(!invalidReported){
                invalidReported = true;
                logger.severe("Cannot read native Finding output; check the Java/native schema. Further parse errors suppressed.");
            }
            return;
        }

        if(details != null)
            details.offer(json);

        String level = finding.field("level");

        if(level.equals("trace"))
            return;

        if(finding.field("check").equals("timer.baseline.v1")){
            String name=playerName.apply(finding.field("player"));
            if(name==null || name.isEmpty())name=finding.field("player");
            logger.info("Timer | " + clean(name,48)
                + " | rate=" + clean(finding.evidence.getOrDefault("score_pps","?"),24)
                + " | tail=" + clean(finding.evidence.getOrDefault("tail_p","?"),24)
                + " | " + clean(finding.field("message"),64));
            return;
        }

        // Unknown future levels remain visible instead of being silently discarded.
        String label = level.equals("suspicious") ? "SUSPICIOUS" : "FINDING";
        String name = playerName.apply(finding.field("player"));

        if(name == null || name.isEmpty())
            name = finding.field("player");

        logger.warning(label + " | " + clean(name, 48) + " | " + summary(finding));
    }

    // Display measurements already produced by the check; do not recalculate its verdict.
    private static String summary(FindingRecord finding){
        String check = finding.field("check");

        if(check.equals("fastbreak.request.v1")){
            String observed = milliseconds(finding.evidence.get("observed_ms"));
            String expected = milliseconds(finding.evidence.get("expected_ms"));
            String samples = finding.evidence.get("samples");

            return "FastBreak | " + observed + " / " + expected + " expected"
                + " | samples=" + clean(samples == null ? "?" : samples, 10);
        }

        if(check.equals("reach.stationary.v1")){
            return "Reach | " + decimal(finding.evidence.get("minimum_distance")) + " blocks"
                + " / " + decimal(finding.evidence.get("allowed_distance")) + " allowed"
                + " | samples=" + clean(finding.evidence.getOrDefault("samples", "?"), 10);
        }

        if(check.equals("autoclicker.cadence.v1")){
            return "Autoclicker | " + decimal(finding.evidence.get("attack_cps")) + " attacks/s"
                + " | regular timing | windows=" + clean(finding.evidence.getOrDefault("samples", "?"), 10);
        }

        // New detectors are readable immediately, even without a custom summary.
        return clean(check, 64) + " | " + clean(finding.field("message"), 120);
    }

    private static String decimal(String text){
        try{
            double value = Double.parseDouble(text);

            if(Double.isFinite(value) && value >= 0 && value <= 1e9)
                return String.format(Locale.ROOT, "%.2f", value);
        }catch(RuntimeException ignored){}

        return "?";
    }

    private static String milliseconds(String text){
        if(text == null || text.length() > 32)
            return "? ms";

        try{
            double value = Double.parseDouble(text);

            if(!Double.isFinite(value) || value < 0 || value > 1.0e9)
                return "? ms";

            return String.format(Locale.ROOT, "%.0f ms", value);
        }catch(NumberFormatException error){
            return "? ms";
        }
    }

    // Keep each console record on one line, without terminal/color control sequences.
    private static String clean(String text, int maximum){
        StringBuilder out = new StringBuilder();
        int limit = Math.min(text.length(), maximum);

        for(int i = 0; i < limit; ++i){
            char c = text.charAt(i);
            out.append(c >= 32 && c <= 126 ? c : '?');
        }

        if(text.length() > maximum)
            out.append("...");

        return out.toString();
    }

    @Override
    public void close(){
        if(closed)
            return;

        closed = true;

        if(details != null)
            details.close();
    }
}
