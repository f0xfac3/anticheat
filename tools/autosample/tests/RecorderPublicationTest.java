import dev.fox.anticheat.capture.CaptureRecorder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

public class RecorderPublicationTest {
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("recorder-publication-");
        Path path=dir.resolve("manifest.json");Files.write(path,"{\"status\":\"open\"}".getBytes("UTF-8"));
        Class<?> option=Class.forName("com.sun.nio.file.ExtendedOpenOption");
        OpenOption denyDelete=(OpenOption)Enum.valueOf((Class)option,"NOSHARE_DELETE");
        Method write=CaptureRecorder.class.getDeclaredMethod("writeJson",Path.class,Map.class);write.setAccessible(true);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> published;
        try(FileChannel held=FileChannel.open(path,StandardOpenOption.READ,denyDelete)){
            published=pool.submit(()->{
                try{write.invoke(null,path,Collections.singletonMap("status","complete"));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            Thread.sleep(150);
            if(published.isDone())throw new AssertionError("Expected writer to wait while NOSHARE_DELETE handle is held");
        }
        try{
            published.get(3,TimeUnit.SECONDS);
            if(!new String(Files.readAllBytes(path),"UTF-8").contains("complete"))throw new AssertionError("manifest not replaced");
            System.out.println("PASS: locked Windows manifest publication retried, then finalized after reader closed");
        }finally{pool.shutdownNow();}
    }
}
