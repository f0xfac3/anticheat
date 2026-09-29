/**
 * LineReader.java assembles UTF-8 log lines without unbounded buffers.
 */

package dev.fox.monitor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class LineReader{
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private boolean oversized;
    private final Consumer<String> line;

    LineReader(Consumer<String> line){
        this.line = line;
    }

    void accept(byte[] bytes, int length){
        for(int i = 0; i < length; ++i){
            int value = bytes[i] & 255;

            if(value == '\n'){
                emit();
            }else if(partial.size() < Finding.MAX_LENGTH + 512){
                partial.write(value);
            }else{
                oversized = true;
            }
        }
    }

    void finish(){
        if(partial.size() > 0 || oversized)
            emit();
    }

    private void emit(){
        String text = new String(partial.toByteArray(), StandardCharsets.UTF_8);

        if(text.endsWith("\r"))
            text = text.substring(0, text.length() - 1);

        if(oversized)
            text = "[monitor] Oversized log line not displayed; inspect the server log.";

        line.accept(text);
        partial.reset();
        oversized = false;
    }
}
