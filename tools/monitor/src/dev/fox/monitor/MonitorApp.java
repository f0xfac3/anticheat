/**
 * MonitorApp.java connects the desktop UI to a separately running Spigot process.
 * A bounded mailbox keeps log readers off the Swing thread.
 */

package dev.fox.monitor;

import java.awt.Desktop;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class MonitorApp{
    final MonitorConfig config;
    final MonitorModel model = new MonitorModel();
    final boolean replay;
    final ServerProcess process;
    final MonitorWindow window;
    private final ArrayBlockingQueue<Message> mailbox = new ArrayBlockingQueue<>(8192);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong pendingChars = new AtomicLong();
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread = new Thread(task, "anticheat-monitor-evidence");
        thread.setDaemon(true);
        return thread;
    });
    private final Timer timer;
    private final ScheduledExecutorService analytics = Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread = new Thread(task, "anticheat-registry"); thread.setDaemon(true); return thread;
    });
    private final Registry registry;
    private volatile boolean closed;
    private volatile boolean startupComplete;
    private volatile String lastNotice = "";
    private boolean startupErrorShown;

    private static final class Message{
        final String text;
        final boolean finding;

        Message(String text, boolean finding){
            this.text = text;
            this.finding = finding;
        }
    }

    MonitorApp(MonitorConfig config, Path replayFile){
        this.config = config;
        replay = replayFile != null;
        process = new ServerProcess(
            line->offer(line, false),
            this::notice
        );
        process.state = replay ? "Not started" : "Starting";
        window = new MonitorWindow(this);
        registry = new Registry(config);
        if(!replay) analytics.scheduleWithFixedDelay(()->{
            Registry.Snapshot snapshot = registry.read();
            SwingUtilities.invokeLater(()->{ if(!closed) window.registry(snapshot); });
        }, 0, 2, TimeUnit.SECONDS);
        timer = new Timer(250, event->drain());
        timer.start();
        window.setVisible(true);

        Thread worker = new Thread(
            ()->{
                if(replay)
                    readReplay(replayFile);
                else
                    runServer();
            },
            "anticheat-monitor-server"
        );
        worker.setDaemon(true);
        worker.start();
    }

    private void offer(String text, boolean finding){
        if(closed)
            return;

        long pending = pendingChars.addAndGet(text.length());

        if(pending > 4_000_000 || !mailbox.offer(new Message(text, finding))){
            pendingChars.addAndGet(-text.length());
            dropped.incrementAndGet();
        }
    }

    private void notice(String text){
        lastNotice = text;
        offer("[monitor] " + text, false);
    }

    private void runServer(){
        EvidenceTail tail = new EvidenceTail(
            config.server.resolve("plugins/FoxAntiCheat/logs"),
            line->offer(line, true),
            this::notice
        );

        try{
            tail.prime();
            watcher.scheduleWithFixedDelay(tail::poll, 100, 250, TimeUnit.MILLISECONDS);
        }catch(Exception error){
            notice("Evidence file feed disabled: " + error + ". Raw console JSON can still be displayed.");
        }

        process.run(config);

        // Give the reporter a short final drain after its server process exits.
        try{
            Thread.sleep(750);
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }

        watcher.shutdown();

        try{
            watcher.awaitTermination(2, TimeUnit.SECONDS);
        }catch(InterruptedException ignored){
            Thread.currentThread().interrupt();
        }

        startupComplete = true;
    }

    private void readReplay(Path path){
        notice("REPLAY: " + path + ". No server was started.");

        try(InputStream input = Files.newInputStream(path)){
            LineReader reader = new LineReader(line->{
                // Replay is not realtime: wait for UI capacity instead of dropping the imported file.
                Message message = new Message(line, line.trim().startsWith("{"));

                while(!closed){
                    try{
                        long pending = pendingChars.addAndGet(line.length());

                        if(pending <= 4_000_000 && mailbox.offer(message, 100, TimeUnit.MILLISECONDS))
                            return;

                        pendingChars.addAndGet(-line.length());
                        Thread.sleep(10);
                    }catch(InterruptedException error){
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            byte[] bytes = new byte[8192];
            int count;

            while(!closed && (count = input.read(bytes)) != -1)
                reader.accept(bytes, count);

            reader.finish();
        }catch(Exception error){
            notice("Cannot open replay: " + error);
        }

        startupComplete = true;
    }

    // Only this method mutates the display model for live/replay input.
    private void drain(){
        long lost = dropped.getAndSet(0);

        if(lost > 0){
            model.uiDropped += lost;
            model.log("[monitor] UI mailbox full: " + lost + " records skipped in this view. Inspect saved logs.");
        }

        for(int i = 0; i < 1000; ++i){
            Message message = mailbox.poll();

            if(message == null)
                break;

            pendingChars.addAndGet(-message.text.length());

            if(message.finding)
                model.finding(message.text.trim(), "Evidence file", null);
            else
                model.log(message.text);
        }

        window.refresh();

        if(!replay && process.state.equals("Start failed") && startupComplete && !startupErrorShown){
            startupErrorShown = true;
            window.selectView("Server log");
            window.message(lastNotice);
        }
    }

    void openLogs(){
        try{
            Path path = config.home.resolve("logs");
            Files.createDirectories(path);
            Desktop.getDesktop().open(path.toFile());
        }catch(Exception error){
            window.message("Logs: " + config.home.resolve("logs") + "\n" + error.getMessage());
        }
    }

    void replay(Path path){
        new MonitorApp(config, path);
    }

    void close(){
        if(process.alive() || process.starting())
            return;

        closed = true;
        timer.stop();
        watcher.shutdownNow();
        analytics.shutdownNow();
        registry.close();
        window.dispose();
    }

    public static void main(String[] arguments){
        SwingUtilities.invokeLater(()->{
            Theme.install();

            try{
                Path home = Paths.get(MonitorApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();

                if(!Files.isDirectory(home))
                    home = home.getParent();

                Path replay = null;

                for(int i = 0; i < arguments.length; ++i){
                    if(arguments[i].equals("--home") && i + 1 < arguments.length)
                        home = Paths.get(arguments[++i]).toAbsolutePath();
                    else if(arguments[i].equals("--replay") && i + 1 < arguments.length)
                        replay = Paths.get(arguments[++i]).toAbsolutePath();
                    else
                        throw new IllegalArgumentException("Unknown/incomplete argument: " + arguments[i]);
                }

                new MonitorApp(new MonitorConfig(home), replay);
            }catch(Exception error){
                JOptionPane.showMessageDialog(
                    null,
                    error.toString(),
                    "Cannot open anticheat monitor",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
}
