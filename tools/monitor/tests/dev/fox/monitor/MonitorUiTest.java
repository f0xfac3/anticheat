package dev.fox.monitor;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Desktop regression render. Uses recorded evidence, and never starts a server. */
public final class MonitorUiTest {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2)
            throw new IllegalArgumentException(
                "replay file, screenshot prefix, optional monitor home");
        Path home = arguments.length > 2 ? Paths.get(arguments[2])
                                         : Files.createTempDirectory("monitor-ui-test");
        MonitorConfig config = new MonitorConfig(home);
        AtomicReference<MonitorApp> app = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            app.set(new MonitorApp(config, Paths.get(arguments[0])));
        });
        Thread.sleep(1500);
        Registry.Snapshot data;
        try (Registry registry = new Registry(config)) {
            data = registry.read();
        }
        SwingUtilities.invokeAndWait(() -> {
            MonitorWindow window = app.get().window;
            if (!data.error.isEmpty())
                System.out.println("Registry: " + data.error);
            window.registry(data);
            window.refresh();
            MonitorTest.require(window.navigation.size() == 4, "Exactly four workspaces");
            MonitorTest.require(window.serverStatus.getText().contains("REPLAY"),
                "Offline evidence must be labeled");
            MonitorTest.require(!window.stop.isEnabled(), "Replay cannot stop server");
            MonitorTest.require(app.get().model.received > 0, "Recorded findings loaded");
            window.selectView("Incidents");
            MonitorTest.require(
                window.incidents.table.getRowCount() > 0, "Recorded incidents visible");
            window.incidents.table.setRowSelectionInterval(0, 0);
            MonitorTest.require(!window.detail.getText().isEmpty(), "Evidence can be inspected");
            for (String page : new String[] {"Overview", "Incidents", "Detections", "Validation"}) {
                window.selectView(page);
                if (page.equals("Detections")
                    && window.workspaces.detections.table.getRowCount() > 0)
                    window.workspaces.detections.table.setRowSelectionInterval(
                        window.workspaces.detections.table.getRowCount() - 1,
                        window.workspaces.detections.table.getRowCount() - 1);
                window.setSize(1450, 900);
                window.validate();
                BufferedImage image = new BufferedImage(
                    window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = image.createGraphics();
                window.paint(graphics);
                graphics.dispose();
                try {
                    ImageIO.write(
                        image, "png", Paths.get(arguments[1] + "-" + page + ".png").toFile());
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }
            if (window.workspaces.detections.table.getRowCount() > 0) {
                window.selectView("Detections");
                window.workspaces.nextExperiment();
            }
        });
        if (!data.rows("detections").isEmpty()) {
            AtomicBoolean ready = new AtomicBoolean();
            for (int i = 0; i < 200 && !ready.get(); i++) {
                Thread.sleep(100);
                SwingUtilities.invokeAndWait(() -> ready.set(
                    app.get().window.workspaces.detectionDetail.getText().startsWith("Next experiment:")));
            }
            MonitorTest.require(ready.get(), "Planner CLI result shown in detection detail");
            SwingUtilities.invokeAndWait(() -> {
                MonitorWindow window = app.get().window;
                BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = image.createGraphics();
                window.paint(graphics);
                graphics.dispose();
                try {
                    ImageIO.write(image, "png", Paths.get(arguments[1] + "-Plan.png").toFile());
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            MonitorWindow window = app.get().window;
            app.get().close();
            MonitorTest.require(!window.isDisplayable(), "Replay closes without server");
            System.out.println("PASS four-workspace UI, recorded evidence, replay isolation, "
                               + "registry, planner CLI and renders");
        });
    }
}
