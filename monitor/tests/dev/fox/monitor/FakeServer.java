/** Test subprocess only. Never packaged into the desktop application. */
package dev.fox.monitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class FakeServer{
    public static void main(String[] arguments) throws Exception{
        System.out.println("working-directory=" + System.getProperty("user.dir"));
        System.out.println("[12:00:00 INFO]: Done (0.1s)! For help, type \"help\"");
        System.err.println("[12:00:00 WARN]: stderr captured");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String command;

        while((command = input.readLine()) != null){
            if(command.equals("stop")){
                System.out.println("Saved and stopped");
                break;
            }

            System.out.println("command=" + command);
        }
    }
}
