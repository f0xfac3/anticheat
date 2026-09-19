/**
 * FindingRecord.java reads the native Finding JSON for display.
 * The current schema contains string fields and one string-valued evidence object.
 */

package dev.fox.anticheat.report;

import java.util.HashMap;
import java.util.Map;

final class FindingRecord{
    static final int MAX_LENGTH = 65536;

    final Map<String, String> fields = new HashMap<>();
    final Map<String, String> evidence = new HashMap<>();

    String field(String name){
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    static FindingRecord read(String json){
        if(json == null || json.length() > MAX_LENGTH)
            throw new IllegalArgumentException("Missing or oversized Finding");

        // Native output is one JSON object per line, not arbitrary JSON input.
        if(json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Expected a single-line Finding");

        Reader reader = new Reader(json);
        FindingRecord result = new FindingRecord();
        reader.object(result.fields, result.evidence);
        reader.space();

        if(reader.offset != json.length())
            throw new IllegalArgumentException("Trailing Finding data");

        for(String name : new String[]{"level", "check", "player", "message"}){
            if(result.field(name).isEmpty())
                throw new IllegalArgumentException("Missing Finding field: " + name);
        }

        return result;
    }

    // Only decodes our string-based Finding schema; not a general JSON parser.
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
                char c = text.charAt(offset++);
                int digit;

                if(c >= '0' && c <= '9')
                    digit = c - '0';
                else if(c >= 'a' && c <= 'f')
                    digit = c - 'a' + 10;
                else if(c >= 'A' && c <= 'F')
                    digit = c - 'A' + 10;
                else
                    throw new IllegalArgumentException("Invalid Unicode escape");

                value = value * 16 + digit;
            }

            return (char)value;
        }
    }
}
