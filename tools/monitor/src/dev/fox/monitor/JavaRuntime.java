/**
 * JavaRuntime.java checks the server's Java executable before starting Spigot.
 * Read its output while it runs so a full output pipe cannot stall the check.
 */

package dev.fox.monitor;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class JavaRuntime{
    private static final long TIMEOUT_MS = 15000;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private JavaRuntime(){}

    static void check(Path executable) throws IOException, InterruptedException{
        String output = probe(
            Arrays.asList(
                executable.toString(),
                "-XshowSettings:properties",
                "-version"
            ),
            TIMEOUT_MS
        );

        validate(output, executable);
    }

    // Keep version validation separate from process I/O for targeted tests.
    static void validate(String output, Path executable) throws IOException{
        String version = property(output, "java.specification.version");
        String bits = property(output, "sun.arch.data.model");

        if(!version.equals("1.8") || !bits.equals("64")){
            throw new IOException(
                "Spigot requires Java 8 x64. Found Java " + version + " / " + bits + "-bit.\n"
                + "Executable: " + executable + "\n"
                + "Set java.home in monitor.properties to your Java 8 x64 root folder."
            );
        }
    }

    private static String property(String output, String name){
        for(String line : output.split("\\r?\\n")){
            int equals = line.indexOf('=');

            if(equals >= 0 && line.substring(0, equals).trim().equals(name))
                return line.substring(equals + 1).trim();
        }

        return "unknown";
    }

    // A separate reader drains both streams while the caller waits for process exit.
    static String probe(List<String> command, long timeoutMs)
        throws IOException, InterruptedException{
        if(timeoutMs <= 0)
            throw new IllegalArgumentException("Java check timeout must be positive");

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        FutureTask<String> output = new FutureTask<>(()->read(process.getInputStream()));
        Thread reader = new Thread(output, "anticheat-java-check-output");
        reader.setDaemon(true);
        reader.start();

        try{
            // This command does not accept input; close stdin instead of leaving it open.
            process.getOutputStream().close();

            if(!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)){
                throw new IOException(
                    "Java version check timed out after " + timeoutMs / 1000.0 + " seconds.\n"
                    + "Executable: " + command.get(0) + "\n"
                    + "Try that executable with -version, or set java.home in monitor.properties."
                );
            }

            String text;

            try{
                text = output.get(2, TimeUnit.SECONDS);
            }catch(ExecutionException error){
                throw new IOException("Cannot read Java version output: " + error.getCause(), error.getCause());
            }catch(TimeoutException error){
                throw new IOException("Java exited but its version output did not close: " + command.get(0), error);
            }

            if(process.exitValue() != 0){
                throw new IOException(
                    "Java version check exited with code " + process.exitValue() + ".\n"
                    + "Executable: " + command.get(0) + "\n"
                    + text.substring(0, Math.min(text.length(), 2000)).trim()
                );
            }

            return text;
        }finally{
            // Timeout/interruption must not leave the probe process running.
            if(process.isAlive())
                process.destroyForcibly();

            close(process.getOutputStream());
            close(process.getInputStream());
            close(process.getErrorStream());
            output.cancel(true);
        }
    }

    private static String read(InputStream input) throws IOException{
        try(InputStream stream = input){
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            boolean exceeded = false;
            int count;

            while((count = stream.read(bytes)) != -1){
                int keep = Math.min(count, MAX_OUTPUT_BYTES - result.size());
                result.write(bytes, 0, keep);

                // Keep draining even after the capture limit, rather than blocking the child.
                if(keep != count)
                    exceeded = true;
            }

            if(exceeded)
                throw new IOException("Java version output exceeds 256 KiB");

            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void close(Closeable stream){
        try{
            stream.close();
        }catch(IOException ignored){
            // Process cleanup must not replace the original startup error.
        }
    }
}
