package dev.fox.anticheat.capture;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/** Bounded asynchronous raw-event journal. A write failure quarantines unfinished trials. */
public final class CaptureRecorder implements AutoCloseable {
    private static final class Item {
        final int kind; final String trial; final byte[] data; final Map<String,Object> metadata;
        Item(int kind,String trial,byte[] data,Map<String,Object> metadata){
            this.kind=kind;this.trial=trial;this.data=data;this.metadata=metadata;
        }
    }
    private final Path boot;
    private final long chunkBytes,quotaBytes;
    private final ArrayBlockingQueue<Item> queue;
    private final AtomicLong diskBytes=new AtomicLong();
    private final Logger log;
    private final Thread worker;
    private volatile boolean closing;
    private volatile String failure;
    private volatile long written;
    private final Map<String,Trial> trials=new HashMap<>(); // worker only

    public CaptureRecorder(Path root,String bootId,Map<String,Object> provenance,long quotaBytes,
                           long chunkBytes,int queueSize,Logger log) throws IOException {
        if(!bootId.matches("[A-Za-z0-9_-]{1,80}")||quotaBytes<1024||chunkBytes<1024||queueSize<2||queueSize>8192)
            throw new IllegalArgumentException("Invalid capture bounds");
        this.log=log;this.quotaBytes=quotaBytes;this.chunkBytes=chunkBytes;this.queue=new ArrayBlockingQueue<>(queueSize);
        Files.createDirectories(root);
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){
            for(Iterator<Path> i=paths.iterator();i.hasNext();){
                Path p=i.next();if(!Files.isSymbolicLink(p)&&Files.isRegularFile(p))diskBytes.addAndGet(Files.size(p));
            }
        }
        if(diskBytes.get()>=quotaBytes)throw new IOException("Dataset disk quota already reached");
        boot=root.resolve(bootId);Files.createDirectory(boot);
        Map<String,Object> info=new LinkedHashMap<>(provenance);info.put("boot_id",bootId);
        info.put("capture_format",1);info.put("wire_schema",3);info.put("status","open");writeJson(boot.resolve("boot.json"),info);
        worker=new Thread(this::run,"fox-raw-capture");worker.setDaemon(true);worker.start();
    }
    public String failure(){return failure;}
    public long written(){return written;}
    public int queued(){return queue.size();}
    public Path directory(){return boot;}
    public boolean begin(String id,Map<String,Object> metadata){
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid trial ID");
        return offer(new Item(0,id,null,new LinkedHashMap<>(metadata)));
    }
    public boolean append(String id,ByteBuffer source){
        ByteBuffer copy=source.duplicate();if(copy.remaining()<48||copy.remaining()>8192){fail("invalid_event_length");return false;}
        byte[] bytes=new byte[copy.remaining()];copy.get(bytes);return offer(new Item(1,id,bytes,null));
    }
    public boolean end(String id,long observedNs,long epochMs,String reason){
        Map<String,Object> m=new LinkedHashMap<>();m.put("end_observed_ns",observedNs);m.put("end_epoch_ms",epochMs);m.put("end_reason",reason);
        return offer(new Item(2,id,null,m));
    }
    private boolean offer(Item item){
        if(closing||failure!=null)return false;
        if(queue.offer(item))return true;
        fail("capture_queue_overflow; unfinished trials are quarantined");return false;
    }
    private void fail(String reason){if(failure==null){failure=reason;log.severe("Raw capture stopped: "+reason);}}
    private void run(){
        try{
            while(!closing||!queue.isEmpty()){
                if(failure!=null)break;
                Item item=queue.poll(200,TimeUnit.MILLISECONDS);if(item==null)continue;
                if(item.kind==0){
                    if(trials.size()>=32||trials.containsKey(item.trial))throw new IOException("Trial capacity/duplicate");
                    trials.put(item.trial,new Trial(item.trial,item.metadata));
                }else{
                    Trial t=trials.get(item.trial);if(t==null)throw new IOException("Missing trial for journal event");
                    if(item.kind==1){t.append(item.data);written++;}
                    else{t.metadata.putAll(item.metadata);t.finish("complete");trials.remove(item.trial);}
                }
            }
        }catch(Exception e){fail(e.toString());}
        finally{
            for(Trial t:trials.values())try{t.finish("quarantined");}catch(IOException e){fail(e.toString());}
            trials.clear();queue.clear();
            Map<String,Object> result=new LinkedHashMap<>();result.put("status",failure==null?"closed":"failed");
            result.put("failure",failure==null?"":failure);result.put("written_events",written);
            try{writeJson(boot.resolve("close.json"),result);}catch(IOException e){fail(e.toString());}
        }
    }
    private final class Trial{
        final Path directory;final Map<String,Object> metadata;OutputStream stream;
        long bytes,records,chunkSize,lastDiskCheck;int chunks;
        Trial(String id,Map<String,Object> metadata)throws IOException{
            directory=boot.resolve(id);Files.createDirectory(directory);this.metadata=metadata;
            metadata.put("trial_id",id);metadata.put("status","open");writeJson(directory.resolve("manifest.json"),metadata);
        }
        void append(byte[] event)throws IOException{
            if(stream==null||chunkSize+event.length+4>chunkBytes){
                if(stream!=null)stream.close();
                Path file=directory.resolve(String.format(Locale.ROOT,"events-%05d.acbin.gz",chunks++));
                OutputStream raw=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW);
                stream=new GZIPOutputStream(new BufferedOutputStream(new FilterOutputStream(raw){
                    @Override public void write(byte[] b,int off,int len)throws IOException{
                        if(diskBytes.addAndGet(len)>quotaBytes)throw new IOException("Dataset disk quota reached");
                        out.write(b,off,len);
                    }
                    @Override public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                },65536));chunkSize=0;
            }
            long now=System.nanoTime();
            if(now-lastDiskCheck>1_000_000_000L){
                lastDiskCheck=now;
                if(Files.getFileStore(directory).getUsableSpace()<2L*1024*1024*1024)throw new IOException("Less than 2 GiB free disk space");
            }
            int n=event.length;stream.write(n);stream.write(n>>>8);stream.write(n>>>16);stream.write(n>>>24);stream.write(event);
            records++;bytes+=n+4;chunkSize+=n+4;
        }
        void finish(String status)throws IOException{
            if(stream!=null){stream.close();stream=null;}
            metadata.put("status",status);metadata.put("records",records);metadata.put("uncompressed_bytes",bytes);metadata.put("chunks",chunks);
            writeJson(directory.resolve("manifest.json"),metadata);
        }
    }
    public static String json(Map<String,Object> object){
        StringBuilder b=new StringBuilder("{");boolean first=true;
        for(Map.Entry<String,Object> e:object.entrySet()){
            if(!first)b.append(',');first=false;b.append(quote(e.getKey())).append(':');Object v=e.getValue();
            b.append(v instanceof Number||v instanceof Boolean?v.toString():quote(String.valueOf(v)));
        }
        return b.append("}\n").toString();
    }
    private static String quote(String s){
        StringBuilder b=new StringBuilder("\"");
        for(char c:s.toCharArray()){
            if(c=='"'||c=='\\')b.append('\\').append(c);
            else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);
        }
        return b.append('"').toString();
    }
    private static void writeJson(Path path,Map<String,Object> data)throws IOException{
        Path tmp=path.resolveSibling(path.getFileName()+".tmp");Files.write(tmp,json(data).getBytes(StandardCharsets.UTF_8));
        // A Windows reader or indexer can briefly hold the destination without
        // FILE_SHARE_DELETE. Retry on the writer thread, preserving atomic publication.
        for(int attempt=0;;attempt++){
            try{
                try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(AccessDeniedException e){
                if(attempt>=39)throw e;
                try{Thread.sleep(25);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Interrupted publishing capture manifest",interrupted);}
            }
        }
    }
    @Override public void close(){
        closing=true;try{worker.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(worker.isAlive())fail("writer_did_not_finish_shutdown; open manifests must not be trained on");
    }
}
