/**
 * Finding.java reads the existing string-based Finding JSON for display.
 * It never decides whether behavior is suspicious.
 */

package dev.fox.monitor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;

final class Finding{
    static final int MAX_LENGTH = 65536;
    final Map<String, String> fields = new LinkedHashMap<>();
    final Map<String, String> evidence = new LinkedHashMap<>();
    String json;
    String sourceTime;

    String field(String name){
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    boolean suspicious(){
        return field("level").equals("suspicious");
    }

    String detector(){
        if(field("check").equals("fastbreak.request.v1"))
            return "FastBreak";

        if(field("check").equals("reach.stationary.v1"))
            return "Reach";

        if(field("check").equals("autoclicker.cadence.v1"))
            return "Autoclicker";

        return field("check");
    }

    String summary(){
        if(evidence.containsKey("observed_ms") && evidence.containsKey("expected_ms")){
            return ms(evidence.get("observed_ms")) + " observed / "
                + ms(evidence.get("expected_ms")) + " expected";
        }

        if(evidence.containsKey("minimum_distance")){
            return decimal(evidence.get("minimum_distance")) + " blocks / "
                + decimal(evidence.get("allowed_distance")) + " allowed";
        }

        if(evidence.containsKey("attack_cps")){
            return decimal(evidence.get("attack_cps")) + " attacks/s | CV "
                + evidence.get("cv") + " | " + field("message").replace('_', ' ');
        }

        return field("message").replace('_', ' ');
    }

    private static String decimal(String text){
        try{
            double value = Double.parseDouble(text);

            if(Double.isFinite(value) && value >= 0 && value < 1e9)
                return String.format(Locale.ROOT, "%.2f", value);
        }catch(RuntimeException ignored){}

        return "?";
    }

    String time(){
        if(sourceTime != null)
            return sourceTime;

        try{
            return new SimpleDateFormat("HH:mm:ss").format(
                new Date(Long.parseLong(field("epoch_ms")))
            );
        }catch(RuntimeException ignored){
            return "--:--:--";
        }
    }

    static String ms(String text){
        try{
            double value = Double.parseDouble(text);

            if(Double.isFinite(value) && value >= 0 && value < 1e12)
                return String.format(Locale.ROOT, "%.0f ms", value);
        }catch(RuntimeException ignored){}

        return "? ms";
    }

    static Finding read(String json){
        if(json == null || json.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Missing or oversized Finding");

        if(json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Expected a single-line Finding");

        Reader reader = new Reader(json);
        Finding result = new Finding();
        reader.object(result.fields, result.evidence);
        reader.space();

        if(reader.offset != json.length())
            throw new IllegalArgumentException("Trailing Finding data");

        for(String name : new String[]{"level", "check", "player", "message"}){
            if(result.field(name).isEmpty())
                throw new IllegalArgumentException("Missing Finding field: " + name);
        }

        result.json = json;
        return result;
    }

    // This is deliberately limited to the native engine's string-valued schema.
    private static final class Reader{
        private final String text;
        private int offset;

        Reader(String text){
            this.text = text;
        }

        void space(){
            while(offset < text.length()){
                char c = text.charAt(offset);

                if(c != ' ' && c != '\t')
                    break;

                ++offset;
            }
        }

        boolean take(char expected){
            space();

            if(offset >= text.length() || text.charAt(offset) != expected)
                return false;

            ++offset;
            return true;
        }

        void expect(char expected){
            if(!take(expected))
                throw new IllegalArgumentException("Invalid Finding JSON");
        }

        void object(Map<String, String> target, Map<String, String> evidence){
            expect('{');
            boolean hasEvidence = false;

            if(take('}'))
                return;

            do{
                String key = string();
                expect(':');

                if(evidence != null && key.equals("evidence")){
                    if(hasEvidence)
                        throw new IllegalArgumentException("Duplicate evidence object");

                    hasEvidence = true;
                    object(evidence, null);
                }else{
                    if(target.put(key, string()) != null)
                        throw new IllegalArgumentException("Duplicate Finding field");
                }
            }while(take(','));

            expect('}');
        }

        String string(){
            expect('"');
            StringBuilder value = new StringBuilder();

            while(offset < text.length()){
                char c = text.charAt(offset++);

                if(c == '"')
                    return value.toString();

                if(c < 32)
                    throw new IllegalArgumentException("Unescaped control character");

                if(c != '\\'){
                    value.append(c);
                    continue;
                }

                if(offset >= text.length())
                    throw new IllegalArgumentException("Truncated string escape");

                switch(text.charAt(offset++)){
                    case '"':
                        value.append('"');
                        break;
                    case '\\':
                        value.append('\\');
                        break;
                    case '/':
                        value.append('/');
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append(unicode());
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid string escape");
                }
            }

            throw new IllegalArgumentException("Unterminated Finding string");
        }

        char unicode(){
            if(text.length() - offset < 4)
                throw new IllegalArgumentException("Truncated Unicode escape");

            int value = 0;

            for(int i = 0; i < 4; ++i){
                int digit = Character.digit(text.charAt(offset++), 16);

                if(digit < 0)
                    throw new IllegalArgumentException("Invalid Unicode escape");

                value = value * 16 + digit;
            }

            return (char)value;
        }
    }
}
