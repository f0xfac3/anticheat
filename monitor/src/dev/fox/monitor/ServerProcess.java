/**
 * ServerProcess.java starts Spigot as a separate process and captures its console.
 * Commands go to that process's stdin, never to a system shell.
 */

package dev.fox.monitor;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ServerProcess{
    volatile String state = "Not started";
    volatile Path capture;
    private volatile Process child;
    private volatile boolean stopRequested;
    private final ArrayBlockingQueue<String> commands = new ArrayBlockingQueue<>(32);
    private final Consumer<String> lines;
    private final Consumer<String> notices;

    ServerProcess(Consumer<String> lines, Consumer<String> notices){
        this.lines = lines;
        this.notices = notices;
    }

    boolean alive(){
        Process process = child;
        return process != null && process.isAlive();
    }

    boolean starting(){
        return state.equals("Starting");
    }

    // This method runs outside the Swing thread and blocks until the child exits.
    void run(MonitorConfig config){
        state = "Starting";

        try{
            if(!Files.isRegularFile(config.serverJar))
                throw new IOException("Server JAR not found: " + config.serverJar);

            Path java = config.javaExecutable();
            lines.accept("[monitor] Checking server Java: " + java);
            JavaRuntime.check(java);
            lines.accept("[monitor] Java 8 x64 verified. Starting Spigot...");

            if(portOpen(config.serverPort()))
                throw new IOException("Server port is already in use. Stop the existing Spigot server before opening the monitor.");

            try(FileChannel lockFile = FileChannel.open(
                config.server.resolve(".anticheat-monitor.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )){
                FileLock lock = lockFile.tryLock();

                if(lock == null)
                    throw new IOException("Another monitor is already running this server.");

                try{
                    runCommand(
                        Arrays.asList(
                            java.toString(),
                            "-Xms" + config.minimumHeap,
                            "-Xmx" + config.maximumHeap,
                            "-Dfile.encoding=UTF-8",
                            "-Djline.terminal=jline.UnsupportedTerminal",
                            "-jar", config.serverJar.toAbsolutePath().toString(), "nogui"
                        ),
                        config.server,
                        config.home.resolve("logs")
                    );
                }finally{
                    lock.release();
                }
            }
        }catch(Exception error){
            state = "Start failed";
            notices.accept(error.toString());
        }
    }

    static boolean portOpen(int port){
        if(port <= 0 || port > 65535)
            throw new IllegalArgumentException("Invalid server port");

        try(Socket socket = new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        }catch(IOException ignored){
            return false;
        }
    }

    // Shared with the subprocess tests. Raw output is archived before UI formatting.
    void runCommand(List<String> command, Path directory, Path logs) throws Exception{
        Files.createDirectories(logs);
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        capture = logs.resolve(name + "-server.log");
        child = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
        state = "Running";

        if(stopRequested)
            commands.offer("stop");

        Thread input = new Thread(this::writeCommands, "anticheat-monitor-input");
        input.setDaemon(true);
        input.start();

        OutputStream archive = null;

        try{
            archive = new BufferedOutputStream(Files.newOutputStream(capture));
        }catch(IOException error){
            notices.accept("Cannot create the console archive: " + error);
        }

        LineReader reader = new LineReader(lines);

        try(InputStream output = child.getInputStream()){
            byte[] bytes = new byte[8192];
            int count;

            while((count = output.read(bytes)) != -1){
                if(archive != null){
                    try{
                        archive.write(bytes, 0, count);
                        archive.flush();
                    }catch(IOException error){
                        notices.accept("Console archive write failed: " + error);
                        try{ archive.close(); }catch(IOException ignored){}
                        archive = null;
                    }
                }

                reader.accept(bytes, count);
            }

            reader.finish();
            int code = child.waitFor();
            state = code == 0 ? "Stopped" : "Exited (" + code + ")";
            notices.accept("Server process exited with code " + code + ".");
        }finally{
            if(archive != null){
                try{
                    archive.close();
                }catch(IOException error){
                    notices.accept("Could not close console archive: " + error);
                }
            }

            if(!alive())
                input.interrupt();
        }
    }

    private void writeCommands(){
        try{
            OutputStream input = child.getOutputStream();

            while(alive()){
                String command = commands.poll(250, TimeUnit.MILLISECONDS);

                if(command == null)
                    continue;

                input.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                input.flush();
            }
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }catch(IOException error){
            if(alive())
                notices.accept("Cannot send server command: " + error);
        }
    }

    void command(String command){
        String text = command.trim();

        if(!alive()){
            notices.accept("The server is not running.");
            return;
        }

        if(text.isEmpty() || text.length() > 512 || text.contains("\n") || text.contains("\r")){
            notices.accept("Enter a single server command (maximum 512 characters).");
            return;
        }

        if(!commands.offer(text))
            notices.accept("Server command queue is full; command not sent.");
    }

    void stop(){
        stopRequested = true;

        if(alive()){
            state = "Stopping";
            command("stop");
        }
    }

    // Only called after an explicit warning and user confirmation.
    void forceStop(){
        if(alive())
            child.destroyForcibly();
    }
}
