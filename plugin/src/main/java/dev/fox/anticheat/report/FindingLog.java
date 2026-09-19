/**
 * FindingLog.java keeps full JSON evidence in rotating files without writing
 * to disk from the observation-processing thread.
 */

package dev.fox.anticheat.report;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class FindingLog implements AutoCloseable{
    private final Logger logger;
    private final Handler file;
    private final ArrayBlockingQueue<String> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Thread worker;
    private volatile boolean accepting = true;
    private long lastWarning;

    FindingLog(File directory, Logger logger) throws IOException{
        this(open(directory), logger, 1024);
    }

    // The worker owns the file; producers only submit bounded immutable strings.
    FindingLog(Handler file, Logger logger, int capacity){
        this.logger = logger;
        this.file = file;
        queue = new ArrayBlockingQueue<>(capacity);

        file.setErrorManager(new ErrorManager(){
            @Override
            public void error(String message, Exception error, int code){
                if(failed.compareAndSet(false, true)){
                    logger.severe("Evidence file write failed; console alerts remain active.");
                }
            }
        });

        worker = new Thread(this::write, "FoxAntiCheat-evidence");
        worker.setDaemon(true);
        worker.start();
    }

    // Keep three files of approximately 5 MiB each, appending across restarts.
    private static Handler open(File directory) throws IOException{
        if(!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create evidence directory: " + directory);

        String parent = directory.getAbsolutePath().replace("%", "%%");
        FileHandler file = new FileHandler(
            parent + File.separator + "findings-%g.jsonl",
            5 * 1024 * 1024,
            3,
            true
        );

        try{
            file.setEncoding("UTF-8");
            file.setLevel(Level.ALL);
            file.setFormatter(new Formatter(){
                @Override
                public String format(LogRecord record){
                    return record.getMessage() + "\n";
                }
            });
            return file;
        }catch(IOException | RuntimeException error){
            file.close();
            throw error;
        }
    }

    // Never delay detection waiting for disk space or a free queue slot.
    void offer(String json){
        if(!accepting || failed.get())
            return;

        if(!queue.offer(json))
            dropped.incrementAndGet();
    }

    private void warnDropped(boolean flush){
        long now = System.nanoTime();

        if(!flush && now - lastWarning < TimeUnit.SECONDS.toNanos(1))
            return;

        lastWarning = now;
        long count = dropped.getAndSet(0);

        if(count != 0){
            logger.warning(
                "Evidence queue full: " + count + " records not saved; console alerts remain active."
            );
        }
    }

    private void write(){
        try{
            while(accepting || !queue.isEmpty()){
                String json = queue.poll(250, TimeUnit.MILLISECONDS);

                if(json != null && !failed.get())
                    file.publish(new LogRecord(Level.INFO, json));

                warnDropped(false);
            }
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            logger.warning("Evidence writer interrupted; queued records may not be saved.");
        }catch(RuntimeException error){
            failed.set(true);
            logger.log(Level.SEVERE, "Evidence writer stopped; console alerts remain active.", error);
        }finally{
            accepting = false;
            warnDropped(true);
            file.close();
        }
    }

    // Drain normally, but do not let a stalled disk block server shutdown indefinitely.
    @Override
    public void close(){
        accepting = false;

        try{
            worker.join(2000);

            if(worker.isAlive()){
                logger.warning("Evidence writer is still draining; pending records are not yet saved.");
            }
        }catch(InterruptedException error){
            Thread.currentThread().interrupt();
            logger.warning("Evidence shutdown interrupted; pending records are not yet saved.");
        }
    }
}
