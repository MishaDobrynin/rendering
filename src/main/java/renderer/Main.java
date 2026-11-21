package renderer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal scene-first desktop interface with native Metal acceleration and a portable CPU fallback. */
public final class Main extends JFrame {
    private static final Color BG = new Color(17, 20, 25);
    private static final Color PANEL = new Color(24, 28, 35);
    private static final Color LINE = new Color(47, 54, 64);
    private static final Color TEXT = new Color(225, 230, 236);
    private static final Color MUTED = new Color(142, 153, 166);
    private static final Color ACCENT = new Color(105, 157, 190);

    private final RenderPanel viewport = new RenderPanel();
    private final JLabel modelInfo = new JLabel("No model loaded");
    private final JLabel status = new JLabel("Ready");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton openButton = button("Open model");
    private final JButton textureButton = button("Texture");
    private final JButton previewButton = button("Preview");
    private final JButton renderButton = primaryButton("Render PNG");
    private final JButton cancelButton = button("Cancel");

    private final JComboBox<String> environment = new JComboBox<>(new String[] {"Ocean", "Studio"});
    private final JComboBox<String> rendererBackend = new JComboBox<>(new String[] {"Automatic (Metal on Mac)", "CPU"});
    private final JLabel backendInfo = valueLabel("Checking graphics device…");
    private final JTextField widthField = field("2560");
    private final JTextField heightField = field("1440");
    private final JTextField samplesField = field("64");
    private final JTextField timeField = field("16:30");
    private final JSlider azimuthSlider = new JSlider(0, 359, 225);
    private final JSlider elevationSlider = new JSlider(-10, 90, 24);
    private final JSlider scaleSlider = new JSlider(25, 300, 100);
    private final JSlider waveSlider = new JSlider(0, 300, 100);
    private final JSlider wavelengthSlider = new JSlider(25, 300, 100);
    private final JSlider cloudSlider = new JSlider(0, 100, 62);
    private final JLabel azimuthValue = valueLabel("225°");
    private final JLabel elevationValue = valueLabel("24°");
    private final JLabel scaleValue = valueLabel("100%");
    private final JLabel waveValue = valueLabel("100%");
    private final JLabel wavelengthValue = valueLabel("100%");
    private final JLabel cloudValue = valueLabel("62%");

    private final Camera camera = new Camera();
    private final Timer previewTimer = new Timer(160, e -> renderInteractivePreview());
    private Model model;
    private Scene scene;
    private double timeOfDay = 16.5;
    private SwingWorker<?, ?> worker;
    private AtomicBoolean cancelFlag;
    private Point previousDrag;
    private long renderStarted;
    private long previewRevision;

    public Main() {
        super("Warship Studio 3.1.3 · Metal");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));
        add(buildToolbar(), BorderLayout.NORTH);
        add(viewport, BorderLayout.CENTER);
        add(buildInspector(), BorderLayout.EAST);
        add(buildStatusBar(), BorderLayout.SOUTH);
        installCameraControls();
        previewTimer.setRepeats(false);
        openButton.addActionListener(e -> chooseModel());
        textureButton.setToolTipText("Apply an image using the OBJ's existing UV map when available");
        openButton.setToolTipText("Open a Wavefront OBJ model");
        textureButton.addActionListener(e -> chooseTexture());
        previewButton.addActionListener(e -> renderPreview());
        renderButton.addActionListener(e -> renderFinal());
        cancelButton.addActionListener(e -> cancelRender());
        environment.addActionListener(e -> rebuildScene());
        rendererBackend.addActionListener(e -> { updateBackendInfo(); schedulePreview(); });
        installLightingListeners();
        installSceneListeners();
        setMinimumSize(new Dimension(1050, 680));
        setSize(1280, 820);
        setLocationRelativeTo(null);
        updateBackendInfo();
        refreshButtons();
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        JLabel brand = new JLabel("WARSHIP STUDIO");
        brand.setForeground(TEXT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 12f));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        actions.add(openButton);
        actions.add(textureButton);
        actions.add(previewButton);
        actions.add(renderButton);
        actions.add(cancelButton);
        bar.add(brand, BorderLayout.WEST);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private JScrollPane buildInspector() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(17, 15, 17, 15));
        panel.setPreferredSize(new Dimension(285, 10));

        panel.add(section("MODEL"));
        modelInfo.setForeground(MUTED);
        modelInfo.setFont(modelInfo.getFont().deriveFont(11f));
        modelInfo.setVerticalAlignment(SwingConstants.TOP);
        modelInfo.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(modelInfo);
        panel.add(Box.createVerticalStrut(12));
        panel.add(section("SCENE"));
        addLabeled(panel, "Environment", environment);
        addSlider(panel, "Model scale", scaleSlider, scaleValue);
        addSlider(panel, "Wave amplitude", waveSlider, waveValue);
        addSlider(panel, "Wave length", wavelengthSlider, wavelengthValue);
        addSlider(panel, "Cloud cover", cloudSlider, cloudValue);

        panel.add(Box.createVerticalStrut(15));
        panel.add(section("ENGINE"));
        addLabeled(panel, "Renderer", rendererBackend);
        backendInfo.setForeground(MUTED);
        backendInfo.setFont(backendInfo.getFont().deriveFont(10f));
        backendInfo.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(backendInfo);

        panel.add(Box.createVerticalStrut(15));
        panel.add(section("OUTPUT"));
        JPanel dimensions = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        dimensions.setOpaque(false);
        JPanel width = smallField("Width", widthField);
        JPanel height = smallField("Height", heightField);
        dimensions.add(width);
        dimensions.add(height);
        dimensions.setAlignmentX(LEFT_ALIGNMENT);
        dimensions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        panel.add(dimensions);
        addLabeled(panel, "Samples per pixel", samplesField);
        JLabel hint = new JLabel("No preset resolution limit · PNG is streamed to disk");
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont(10f));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(hint);

        panel.add(Box.createVerticalStrut(15));
        panel.add(section("SUN & SKY"));
        addLabeled(panel, "Time of day (24h)", timeField);
        addSlider(panel, "Sun azimuth", azimuthSlider, azimuthValue);
        addSlider(panel, "Sun elevation", elevationSlider, elevationValue);
        JLabel cameraHint = new JLabel("Drag: orbit   ·   Scroll: zoom");
        cameraHint.setForeground(MUTED);
        cameraHint.setFont(cameraHint.getFont().deriveFont(10f));
        cameraHint.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
        cameraHint.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(cameraHint);
        panel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, LINE));
        scroll.getViewport().setBackground(PANEL);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                BorderFactory.createEmptyBorder(7, 13, 7, 13)));
        status.setForeground(MUTED);
        status.setFont(status.getFont().deriveFont(10.5f));
        progress.setPreferredSize(new Dimension(210, 13));
        progress.setBorderPainted(false);
        progress.setStringPainted(false);
        progress.setVisible(false);
        bar.add(status, BorderLayout.CENTER);
        bar.add(progress, BorderLayout.EAST);
        return bar;
    }

    private void installLightingListeners() {
        timeField.setToolTipText("24-hour time sets sunrise, daylight, sunset, and night sky coloration.");
        azimuthSlider.setToolTipText("Rotate the directional sun around the scene horizon.");
        elevationSlider.setToolTipText("Raise or lower the directional sun above the horizon.");
        widthField.setToolTipText("Custom output width in pixels; no fixed resolution preset.");
        heightField.setToolTipText("Custom output height in pixels; final output streams to PNG.");
        samplesField.setToolTipText("Samples per pixel. Higher values reduce Monte Carlo noise.");
        rendererBackend.setToolTipText("Automatic uses the native Metal compute shader when available; otherwise it uses the Java CPU renderer.");
        timeField.addActionListener(e -> updateLighting());
        timeField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { updateLighting(); }
        });
        azimuthSlider.addChangeListener(e -> {
            azimuthValue.setText(azimuthSlider.getValue() + "°");
            if (!azimuthSlider.getValueIsAdjusting()) schedulePreview();
        });
        elevationSlider.addChangeListener(e -> {
            elevationValue.setText(elevationSlider.getValue() + "°");
            if (!elevationSlider.getValueIsAdjusting()) schedulePreview();
        });
    }

    private void installSceneListeners() {
        scaleSlider.addChangeListener(e -> {
            scaleValue.setText(scaleSlider.getValue() + "%");
            if (!scaleSlider.getValueIsAdjusting()) rebuildScene();
        });
        waveSlider.addChangeListener(e -> {
            waveValue.setText(waveSlider.getValue() + "%");
            OceanWater.setWaveControls(waveSlider.getValue() / 100.0, wavelengthSlider.getValue() / 100.0);
            if (!waveSlider.getValueIsAdjusting()) schedulePreview();
        });
        wavelengthSlider.addChangeListener(e -> {
            wavelengthValue.setText(wavelengthSlider.getValue() + "%");
            OceanWater.setWaveControls(waveSlider.getValue() / 100.0, wavelengthSlider.getValue() / 100.0);
            if (!wavelengthSlider.getValueIsAdjusting()) schedulePreview();
        });
        cloudSlider.addChangeListener(e -> {
            cloudValue.setText(cloudSlider.getValue() + "%");
            if (!cloudSlider.getValueIsAdjusting()) schedulePreview();
        });
    }

    private void installCameraControls() {
        viewport.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { previousDrag = e.getPoint(); }
            @Override public void mouseReleased(MouseEvent e) {
                if (previousDrag != null && previousDrag.distance(e.getPoint()) > 2) schedulePreview();
                previousDrag = null;
            }
        });
        viewport.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (previousDrag == null) return;
                camera.orbit(e.getX() - previousDrag.x, e.getY() - previousDrag.y);
                previousDrag = e.getPoint();
                schedulePreview();
            }
        });
        viewport.addMouseWheelListener((MouseWheelEvent e) -> {
            camera.zoom(e.getPreciseWheelRotation());
            schedulePreview();
        });
    }

    private void chooseModel() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Wavefront OBJ model");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Wavefront OBJ (*.obj)", "obj"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        if (!source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".obj")) {
            showError("Unsupported model format", new IOException("Warship Studio now imports Wavefront OBJ files only."));
            return;
        }
        boolean ocean = environment.getSelectedIndex() == 0;
        double scale = scaleSlider.getValue() / 100.0;
        runWorker("Loading mesh and materials…", false, () -> {
            Model loaded = ObjLoader.load(source);
            Scene loadedScene = new Scene(loaded, ocean, scale);
            return new LoadedScene(loaded, loadedScene);
        }, result -> {
            model = result.model();
            scene = result.scene();
            resetCamera();
            updateModelInfo();
            viewport.setImage(null);
            setIdle("Loaded " + source.getFileName() + " · " + model.triangles().size() + " triangles"
                    + (model.warnings().isEmpty() ? "" : " · material warnings"));
            if (!model.warnings().isEmpty()) showLoadWarnings(model.warnings());
            renderPreview();
        });
    }

    private void chooseTexture() {
        if (model == null || isBusy()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose texture image");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Texture images (PNG, JPEG, BMP, GIF)", "png", "jpg", "jpeg", "bmp", "gif"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path texturePath = chooser.getSelectedFile().toPath();
        String textureName = texturePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!(textureName.endsWith(".png") || textureName.endsWith(".jpg") || textureName.endsWith(".jpeg")
                || textureName.endsWith(".bmp") || textureName.endsWith(".gif"))) {
            showError("Unsupported texture format", new IOException("Choose a PNG, JPEG, BMP, or GIF texture image."));
            return;
        }
        Model sourceModel = model;
        boolean ocean = environment.getSelectedIndex() == 0;
        double scale = scaleSlider.getValue() / 100.0;
        runWorker("Applying texture and rebuilding acceleration data…", false, () -> {
            BufferedImage image = ImageIO.read(texturePath.toFile());
            if (image == null) throw new IOException("The selected image is not a supported texture format.");
            TextureMapper.ApplyResult mapped = TextureMapper.apply(sourceModel, image);
            Scene rebuilt = new Scene(mapped.model(), ocean, scale);
            return new TextureApplied(mapped, rebuilt);
        }, result -> {
            model = result.mapping().model();
            scene = result.scene();
            updateModelInfo();
            String mappingStatus;
            if (result.mapping().fullyUvMapped()) {
                mappingStatus = "using the OBJ's authored UV map";
            } else if (result.mapping().usedAuthoredUvs()) {
                mappingStatus = result.mapping().authoredUvTriangles() + " faces use OBJ UVs; "
                        + result.mapping().projectedTriangles() + " faces use approximate projection";
            } else {
                mappingStatus = "using approximate box projection (OBJ contains no UV map)";
            }
            setIdle("Applied " + texturePath.getFileName() + " · " + mappingStatus);
            renderPreview();
        });
    }

    private void rebuildScene() {
        if (model == null || isBusy()) return;
        Model source = model;
        boolean ocean = environment.getSelectedIndex() == 0;
        double scale = scaleSlider.getValue() / 100.0;
        runWorker("Updating scene…", false, () -> new Scene(source, ocean, scale), rebuilt -> {
            scene = rebuilt;
            updateModelInfo();
            setIdle("Scene updated");
            renderInteractivePreview();
        });
    }

    private void renderPreview() {
        if (scene == null || isBusy()) return;
        int width = Math.max(320, Math.min(1600, viewport.getWidth()));
        int height = Math.max(200, (int) Math.round(width * (double) Math.max(200, viewport.getHeight()) / Math.max(320, viewport.getWidth())));
        CameraSnapshot cameraSnapshot = camera.snapshot();
        Scene currentScene = scene;
        LightingSettings light = lighting();
        double clouds = cloudSlider.getValue() / 100.0;
        long revision = previewRevision;
        boolean useMetal = shouldUseMetal();
        runWorker(useMetal ? "Rendering Metal preview…" : "Rendering CPU preview…", false,
                () -> useMetal
                        ? new MetalPathTracer(currentScene, clouds, light).renderPreview(cameraSnapshot, width, height, 1)
                        : RasterRenderer.render(currentScene, cameraSnapshot, width, height, 1, clouds, light), image -> {
            viewport.setImage(image);
            setIdle("Preview · " + width + " × " + height + (useMetal ? " · Metal GPU" : " · CPU"));
            if (revision != previewRevision) schedulePreview();
        });
    }

    private void renderInteractivePreview() {
        if (scene == null || isBusy()) return;
        int width = Math.max(240, Math.min(640, viewport.getWidth()));
        int height = Math.max(150, (int) Math.round(width * (double) Math.max(150, viewport.getHeight()) / Math.max(240, viewport.getWidth())));
        CameraSnapshot cameraSnapshot = camera.snapshot();
        Scene currentScene = scene;
        LightingSettings light = lighting();
        double clouds = cloudSlider.getValue() / 100.0;
        long revision = previewRevision;
        boolean useMetal = shouldUseMetal();
        runWorker(useMetal ? "Updating Metal viewport…" : "Updating CPU viewport…", false,
                () -> useMetal
                        ? new MetalPathTracer(currentScene, clouds, light).renderPreview(cameraSnapshot, width, height, 1)
                        : RasterRenderer.render(currentScene, cameraSnapshot, width, height, 1, clouds, light), image -> {
            viewport.setImage(image);
            setIdle("Ready · " + (useMetal ? "Metal GPU" : "CPU") + " · drag to orbit · scroll to zoom");
            if (revision != previewRevision) schedulePreview();
        });
    }

    private void renderFinal() {
        if (scene == null || isBusy()) return;
        final int width, height, spp;
        try {
            width = positiveInt(widthField.getText(), "Width");
            height = positiveInt(heightField.getText(), "Height");
            spp = positiveInt(samplesField.getText(), "Samples per pixel");
            if (spp > 4096) throw new IllegalArgumentException("Samples per pixel cannot exceed 4096.");
        } catch (IllegalArgumentException error) {
            showError("Invalid render settings", error);
            return;
        }
        CameraSnapshot cameraSnapshot = camera.snapshot();
        Scene currentScene = scene;
        LightingSettings light = lighting();
        double clouds = cloudSlider.getValue() / 100.0;
        Path destination = outputPath(width, height, spp);
        boolean useMetal = shouldUseMetal();
        cancelFlag = new AtomicBoolean(false);
        AtomicBoolean cancel = cancelFlag;
        renderStarted = System.nanoTime();
        setBusy(true, "Rendering " + width + " × " + height + " · " + spp + " spp · " + (useMetal ? "Metal GPU" : "CPU"), true);
        progress.setValue(0);
        worker = new SwingWorker<PathTracer.RenderResult, Void>() {
            @Override protected PathTracer.RenderResult doInBackground() throws Exception {
                if (useMetal) {
                    return new MetalPathTracer(currentScene, clouds, light).renderToPng(
                            cameraSnapshot, width, height, spp, destination, (done, total) -> {
                                int percent = (int) Math.round(done * 100.0 / Math.max(1, total));
                                SwingUtilities.invokeLater(() -> {
                                    if (worker == this) {
                                        progress.setValue(percent);
                                        status.setText("Metal rendering · " + percent + "% · " + elapsed(System.nanoTime() - renderStarted));
                                    }
                                });
                            }, cancel);
                }
                PathTracer tracer = new PathTracer(currentScene, clouds, light);
                return tracer.renderToPng(cameraSnapshot, width, height, spp, destination, (done, total) -> {
                    int percent = (int) Math.round(done * 100.0 / Math.max(1, total));
                    SwingUtilities.invokeLater(() -> {
                        if (worker == this) {
                            progress.setValue(percent);
                            status.setText("Rendering · " + percent + "% · " + elapsed(System.nanoTime() - renderStarted));
                        }
                    });
                }, cancel);
            }
            @Override protected void done() {
                try {
                    PathTracer.RenderResult result = get();
                    if (result.preview() != null) viewport.setImage(result.preview());
                    if (result.cancelled()) setIdle("Render cancelled · partial output removed");
                    else setIdle("Saved " + result.savedPath() + " · " + elapsed(System.nanoTime() - renderStarted));
                } catch (CancellationException error) {
                    setIdle("Render cancelled");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    setIdle("Render interrupted");
                } catch (ExecutionException error) {
                    setIdle("Render failed");
                    showError("Raytrace failed", error.getCause());
                }
            }
        };
        worker.execute();
    }

    private boolean shouldUseMetal() {
        return rendererBackend.getSelectedIndex() == 0 && MetalPathTracer.isAvailable();
    }

    private void updateBackendInfo() {
        if (rendererBackend.getSelectedIndex() == 1) {
            backendInfo.setText("Java CPU path tracer");
            return;
        }
        String device = MetalPathTracer.deviceName();
        if (device != null) {
            backendInfo.setText("Metal GPU · " + device);
            backendInfo.setToolTipText("Native Metal compute backend is available.");
        } else {
            backendInfo.setText("Metal unavailable · CPU fallback");
            backendInfo.setToolTipText(MetalPathTracer.unavailableReason());
        }
    }

    private void updateLighting() {
        String[] parts = timeField.getText().trim().split(":");
        try {
            if (parts.length != 2) throw new NumberFormatException();
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) throw new NumberFormatException();
            timeOfDay = hours + minutes / 60.0;
            timeField.setText(String.format("%02d:%02d", hours, minutes));
            schedulePreview();
        } catch (NumberFormatException error) {
            showError("Invalid time", new IllegalArgumentException("Enter time as HH:mm using a 24-hour clock, for example 18:45."));
            timeField.requestFocusInWindow();
        }
    }

    private LightingSettings lighting() {
        return new LightingSettings(timeOfDay, azimuthSlider.getValue(), elevationSlider.getValue());
    }

    private Path outputPath(int width, int height, int spp) {
        String name = model == null ? "scene" : model.source().getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        return Path.of("renders").resolve(name + "_" + stamp + "_" + width + "x" + height + "_" + spp + "spp.png");
    }

    private <T> void runWorker(String message, boolean showProgress, Task<T> task, Success<T> success) {
        setBusy(true, message, showProgress);
        worker = new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.run(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (CancellationException error) { setIdle("Cancelled"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); setIdle("Interrupted"); }
                catch (ExecutionException error) { setIdle("Operation failed"); showError("Operation failed", error.getCause()); }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean busy, String message, boolean showProgress) {
        status.setText(message);
        progress.setVisible(showProgress);
        openButton.setEnabled(!busy);
        textureButton.setEnabled(!busy && model != null);
        previewButton.setEnabled(!busy && scene != null);
        renderButton.setEnabled(!busy && scene != null);
        cancelButton.setEnabled(busy && cancelFlag != null);
        environment.setEnabled(!busy);
        setInspectorEnabled(!busy);
    }

    private void setInspectorEnabled(boolean enabled) {
        widthField.setEnabled(enabled); heightField.setEnabled(enabled); samplesField.setEnabled(enabled);
        rendererBackend.setEnabled(enabled);
        timeField.setEnabled(enabled); azimuthSlider.setEnabled(enabled); elevationSlider.setEnabled(enabled);
        scaleSlider.setEnabled(enabled); waveSlider.setEnabled(enabled); wavelengthSlider.setEnabled(enabled); cloudSlider.setEnabled(enabled);
    }

    private void setIdle(String message) {
        status.setText(message);
        progress.setVisible(false);
        progress.setValue(0);
        worker = null;
        cancelFlag = null;
        refreshButtons();
    }

    private void refreshButtons() {
        boolean busy = isBusy();
        openButton.setEnabled(!busy);
        textureButton.setEnabled(!busy && model != null);
        previewButton.setEnabled(!busy && scene != null);
        renderButton.setEnabled(!busy && scene != null);
        cancelButton.setEnabled(busy && cancelFlag != null);
        environment.setEnabled(!busy);
        setInspectorEnabled(!busy);
    }

    private boolean isBusy() { return worker != null && !worker.isDone(); }

    private void cancelRender() {
        if (cancelFlag != null && isBusy()) {
            cancelFlag.set(true);
            status.setText("Cancelling after current row band…");
        }
    }

    private void schedulePreview() {
        previewRevision++;
        if (scene != null && !isBusy()) previewTimer.restart();
    }

    private void resetCamera() {
        camera.yaw = 0.75;
        camera.pitch = 0.24;
        camera.distance = 6.8;
        camera.target = new Vec3(0, 1.1, 0);
    }

    private void updateModelInfo() {
        if (model == null) { modelInfo.setText("No model loaded"); return; }
        String name = model.source().getFileName().toString();
        int materials = TextureMapper.texturedMaterialCount(model);
        modelInfo.setText("<html><b>" + escape(name) + "</b><br>" + model.triangles().size()
                + " triangles<br>" + materials + " textured materials</html>");
        textureButton.setEnabled(!isBusy());
    }

    private void addSlider(JPanel parent, String caption, JSlider slider, JLabel value) {
        JPanel container = new JPanel(new BorderLayout(3, 3));
        container.setOpaque(false);
        container.setAlignmentX(LEFT_ALIGNMENT);
        container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 49));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel label = smallLabel(caption);
        header.add(label, BorderLayout.WEST);
        header.add(value, BorderLayout.EAST);
        styleSlider(slider);
        container.add(header, BorderLayout.NORTH);
        container.add(slider, BorderLayout.CENTER);
        parent.add(container);
        parent.add(Box.createVerticalStrut(4));
    }

    private void addLabeled(JPanel parent, String caption, java.awt.Component component) {
        JPanel row = new JPanel(new BorderLayout(4, 4));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        row.add(smallLabel(caption), BorderLayout.NORTH);
        styleInput(component);
        row.add(component, BorderLayout.CENTER);
        parent.add(row);
        parent.add(Box.createVerticalStrut(5));
    }

    private JPanel smallField(String caption, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(3, 3));
        panel.setOpaque(false);
        JLabel label = smallLabel(caption);
        panel.add(label, BorderLayout.NORTH);
        styleInput(field);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private JLabel section(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(ACCENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JLabel smallLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(10.5f));
        return label;
    }

    private JLabel valueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(10f));
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setForeground(TEXT);
        button.setBackground(new Color(41, 48, 58));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        button.setFont(button.getFont().deriveFont(11f));
        return button;
    }

    private static JButton primaryButton(String text) {
        JButton button = button(text);
        button.setForeground(Color.WHITE);
        button.setBackground(new Color(48, 91, 119));
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(68, 119, 150)),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        return button;
    }

    private static JTextField field(String text) {
        JTextField field = new JTextField(text, 7);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(new Color(17, 21, 27));
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        field.setFont(field.getFont().deriveFont(11f));
        return field;
    }

    private static void styleInput(java.awt.Component component) {
        component.setFont(component.getFont().deriveFont(11f));
        if (component instanceof JComboBox<?> combo) {
            combo.setForeground(TEXT);
            combo.setBackground(new Color(17, 21, 27));
        }
    }

    private static void styleSlider(JSlider slider) {
        slider.setOpaque(false);
        slider.setForeground(ACCENT);
        slider.setPreferredSize(new Dimension(220, 23));
    }

    private static int positiveInt(String value, String fieldName) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(fieldName + " must be a positive whole number.");
        }
    }

    private void showLoadWarnings(java.util.List<String> warnings) {
        StringBuilder message = new StringBuilder("The model geometry loaded successfully, but some materials could not be resolved.\n\n");
        for (String warning : warnings) message.append("• ").append(warning).append("\n\n");
        message.append("You can still use the model. Use the Texture button to apply an image, or correct the OBJ's mtllib/map_Kd paths for full material loading.");
        JOptionPane.showMessageDialog(this, message.toString(), "Model loaded with material warnings", JOptionPane.WARNING_MESSAGE);
    }

    private void showError(String title, Throwable error) {
        String message = error == null || error.getMessage() == null ? String.valueOf(error) : error.getMessage();
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String elapsed(long nanos) {
        long seconds = Math.max(0, nanos / 1_000_000_000L);
        long hours = seconds / 3600, minutes = (seconds % 3600) / 60, secs = seconds % 60;
        return hours > 0 ? String.format("%dh %02dm %02ds", hours, minutes, secs)
                : String.format("%dm %02ds", minutes, secs);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new Main().setVisible(true);
        });
    }

    private record LoadedScene(Model model, Scene scene) { }
    private record TextureApplied(TextureMapper.ApplyResult mapping, Scene scene) { }
    @FunctionalInterface private interface Task<T> { T run() throws Exception; }
    @FunctionalInterface private interface Success<T> { void accept(T value); }
}
