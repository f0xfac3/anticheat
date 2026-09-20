/** UI smoke test; requires a desktop (or Xvfb), never packaged into the application. */
package dev.fox.monitor;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class MonitorUiTest{
    public static void main(String[] arguments) throws Exception{
        if(arguments.length < 2)
            throw new IllegalArgumentException("Pass a replay file and screenshot path");

        AtomicReference<MonitorApp> app = new AtomicReference<>();
        Path home = Files.createTempDirectory("monitor-ui-test");
        MonitorConfig config = new MonitorConfig(home);

        SwingUtilities.invokeAndWait(()->{
            Theme.install();
            app.set(new MonitorApp(config, Paths.get(arguments[0])));
        });

        for(int i = 0; i < 150; ++i){
            Thread.sleep(100);
            AtomicReference<Long> count = new AtomicReference<>();
            SwingUtilities.invokeAndWait(()->count.set(app.get().model.received));

            if(count.get() == 42)
                break;
        }

        SwingUtilities.invokeAndWait(()->{
            MonitorWindow window = app.get().window;
            MonitorTest.require(app.get().model.received == 42, "replay did not complete");
            window.refresh();
            MonitorTest.require(window.table.getRowCount() == 2, "Alerts does not show two findings");
            MonitorTest.require(window.serverStatus.getText().contains("REPLAY"), "offline data mislabeled live");
            MonitorTest.require(!window.stop.isEnabled(), "replay can stop server");
            System.out.println("PASS default alert view: two real recorded alerts, explicit REPLAY label");
            window.search.setText("251.015");
            MonitorTest.require(window.table.getRowCount() == 1, "alert search failed");
            System.out.println("PASS alert filter and selection");
            window.search.setText("");
            window.selectView("Activity");
            MonitorTest.require(window.table.getRowCount() == 42, "activity count");
            System.out.println("PASS Activity separates trace findings from Alerts");
            window.selectView("Players");
            MonitorTest.require(window.table.getRowCount() == 1, "player table");
            System.out.println("PASS player view shows the recorded player");
            window.selectView("Server log");
            MonitorTest.require(window.serverLog.getText().contains("UUID of player"), "server log empty");
            window.search.setText("UUID of player");
            MonitorTest.require(!window.serverLog.getText().contains("dig_start"), "server log filter stale");
            System.out.println("PASS server log and search");
            window.search.setText("");
            window.selectView("Alerts");
            window.table.setRowSelectionInterval(1, 1);
            window.copy.doClick();

            try{
                String text = (String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                MonitorTest.require(text.contains("NOT_MEASURED") && text.contains("251.015"), "copy evidence failed");
            }catch(Exception error){
                throw new RuntimeException(error);
            }

            System.out.println("PASS selected evidence and Copy JSON preserve original precision/outcome");
            window.setSize(1320, 820);
            window.validate();
            BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            window.paint(graphics);
            graphics.dispose();

            try{
                ImageIO.write(image, "png", Paths.get(arguments[1]).toFile());
            }catch(Exception error){
                throw new RuntimeException(error);
            }

            System.out.println("PASS rendered desktop screenshot");
            app.get().close();
            MonitorTest.require(!window.isDisplayable(), "window did not close");
            System.out.println("PASS replay close releases the window");
        });
        System.out.println("8 desktop UI checks passed");
    }
}
