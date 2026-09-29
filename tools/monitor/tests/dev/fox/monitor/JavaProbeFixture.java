/** Child process for JavaRuntimeTest; never shipped inside the monitor JAR. */
package dev.fox.monitor;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

public final class JavaProbeFixture{
    private static void version(){
        System.err.println("    java.specification.version = 1.8");
        System.err.println("    sun.arch.data.model = 64");
        System.err.flush();
    }

    public static void main(String[] args) throws Exception{
        String mode = args[0];

        if(mode.equals("wait")){
            Path lockPath = Paths.get(args[1]);

            try(FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ); FileLock lock = channel.lock()){
                Files.write(Paths.get(args[2]), new byte[]{1});
                version();
                Thread.sleep(60000);
            }

            return;
        }

        if(mode.equals("stdin")){
            while(System.in.read() != -1){}

            version();
            return;
        }

        if(mode.equals("nonzero")){
            System.err.println("probe deliberately failed");
            System.exit(7);
        }

        if(mode.equals("flood") || mode.equals("oversize")){
            byte[] block = new byte[1024];
            Arrays.fill(block, (byte)'x');
            block[block.length - 1] = '\n';
            int count = mode.equals("flood") ? 128 : 512;

            for(int i = 0; i < count; ++i){
                if(i % 2 == 0)
                    System.out.write(block);
                else
                    System.err.write(block);
            }

            System.out.flush();
            System.err.flush();
        }

        version();
    }
}
