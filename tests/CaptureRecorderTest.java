package dev.fox.anticheat.capture;

import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.event.*;
import dev.fox.anticheat.packet.PacketInfo;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

public final class CaptureRecorderTest {
    private static int passed;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Paths.get("."),"capture-test-");EventWriter w=new EventWriter();
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("declared_label","legit");metadata.put("quoted","test\"\\\nvalue");
        CaptureRecorder r=new CaptureRecorder(root,"test-boot",Collections.emptyMap(),8*1024*1024,1024,1024,Logger.getAnonymousLogger());
        check(r.begin("trial-one",metadata),"begin accepted");
        ByteBuffer start=w.begin(1,1,1,0,0,0).session("test-player",47,10808).finish();
        r.append("trial-one",start);check(start.position()==0,"capture preserves source buffer position");
        MovementContext c=new MovementContext();c.world="world";c.available=c.clearPath=true;c.friction=.6;c.movementSpeed=.1;c.jumpVelocity=.42;
        for(int i=0;i<100;i++){
            long t=(i+1)*50_000_000L;
            r.append("trial-one",w.begin(11,1,i+2,t,0,i).movement(new MovementEvent(new PacketInfo(i+1,i+1,t,0),i*.1,64,0,0,0,true,false,true),c,t).finish());
        }
        check(r.end("trial-one",6_000_000_000L,6000,"operator_stop"),"completion queued");r.close();
        check(r.failure()==null&&r.written()==101,"all immutable event copies written");
        Path dir=root.resolve("test-boot/trial-one");int frames=0,chunks=0;
        try(java.util.stream.Stream<Path> list=Files.list(dir)){
            for(Iterator<Path> it=list.sorted().iterator();it.hasNext();){Path p=it.next();if(!p.toString().endsWith(".gz"))continue;chunks++;
                try(InputStream in=new GZIPInputStream(Files.newInputStream(p))){
                    while(true){int lo=in.read();if(lo==-1)break;int a=in.read(),b=in.read(),d=in.read();check(a>=0&&b>=0&&d>=0,"complete frame prefix");
                        int n=lo|(a<<8)|(b<<16)|(d<<24);byte[] data=new byte[n];new DataInputStream(in).readFully(data);
                        ByteBuffer event=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                        check(event.getInt()==0x43415846&&event.getLong(16)==frames+1,"exact ordered FXAC frame");frames++;
                    }
                }
            }
        }
        check(frames==101&&chunks>1,"rotated gzip chunks retain every record");
        String manifest=new String(Files.readAllBytes(dir.resolve("manifest.json")),"UTF-8");
        check(manifest.contains("\"status\":\"complete\"")&&manifest.contains("\"records\":101"),"manifest finalized after data");
        check(manifest.contains("test\\\"\\\\\\u000avalue"),"metadata JSON escaped");
        r=new CaptureRecorder(root,"unfinished",Collections.emptyMap(),8*1024*1024,1024,8,Logger.getAnonymousLogger());
        r.begin("trial-open",metadata);r.append("trial-open",w.begin(1,1,1,0,0,0).session("p",47,10808).finish());r.close();
        check(new String(Files.readAllBytes(root.resolve("unfinished/trial-open/manifest.json")),"UTF-8").contains("quarantined"),"unfinished trial is not trainable");
        r=new CaptureRecorder(root,"bad-event",Collections.emptyMap(),8*1024*1024,1024,8,Logger.getAnonymousLogger());
        check(!r.append("bad",ByteBuffer.allocate(9))&&r.failure()!=null,"invalid capture stops explicitly");r.close();
        check(!r.begin("after-close",metadata),"closed recorder rejects starts");
        System.out.println(passed+" capture checks passed; fixtures="+root.toAbsolutePath());
    }
}
