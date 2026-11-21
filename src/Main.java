package renderer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JSlider;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main extends JFrame {
    private final RenderPanel renderPanel = new RenderPanel();
    private final JLabel modelLabel = new JLabel("No model loaded");
    private final JLabel statusLabel = new JLabel("Ready");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton loadButton = new JButton("Load Model…");
    private final JButton previewButton = new JButton("Preview (Raster)");
    private final JButton traceButton = new JButton("Raytrace Scene");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton saveButton = new JButton("Save PNG…");
    private final JComboBox<String> qualityBox = new JComboBox<>(new String[] {
            "Draft · 4 samples", "Standard · 16 samples", "Cinematic · 64 samples",
            "Extended · 128 samples", "Very High · 256 samples", "Extreme · 512 samples"
    });
    private final JComboBox<String> resolutionBox = new JComboBox<>(new String[] {
            "640 × 360", "960 × 540", "1280 × 720", "1920 × 1080", "2560 × 1440", "3840 × 2160"
    });
    private final JComboBox<String> environmentBox = new JComboBox<>(new String[] {
            "Ocean · Gerstner waves", "Studio · Ground plane"
    });
    private final JSlider modelScaleSlider = new JSlider(25, 300, 100);
    private final JSlider waveHeightSlider = new JSlider(0, 300, 100);
    private final JSlider waveLengthSlider = new JSlider(25, 300, 100);
    private final javax.swing.Timer interactiveTimer = new javax.swing.Timer(140, e -> renderInteractivePreview());
    private double modelScale = 1.0;

    private Scene scene;
    private Model currentModel;
    private final Camera camera = new Camera();
    private SwingWorker<?, ?> currentWorker;
    private AtomicBoolean cancelFlag;
    private Point dragStart;
    private long renderStartNanos;

    public Main() {
        super("Warship & Armour Renderer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(new Color(24, 30, 38));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildSidebar(), BorderLayout.WEST);
        add(renderPanel, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        installCameraControls();
        environmentBox.addActionListener(e -> rebuildEnvironment());
        interactiveTimer.setRepeats(false);
        modelScaleSlider.addChangeListener(e -> {
            modelScale = modelScaleSlider.getValue() / 100.0;
            if (!modelScaleSlider.getValueIsAdjusting()) rebuildSceneForControls();
        });
        waveHeightSlider.addChangeListener(e -> {
            OceanWater.setWaveControls(waveHeightSlider.getValue() / 100.0, waveLengthSlider.getValue() / 100.0);
            if (!waveHeightSlider.getValueIsAdjusting()) rebuildSceneForControls();
        });
        waveLengthSlider.addChangeListener(e -> {
            OceanWater.setWaveControls(waveHeightSlider.getValue() / 100.0, waveLengthSlider.getValue() / 100.0);
            if (!waveLengthSlider.getValueIsAdjusting()) rebuildSceneForControls();
        });
        setMinimumSize(new Dimension(900, 620));
        setSize(1320, 850);
        setLocationRelativeTo(null);
        updateButtons();
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.setBackground(new Color(31, 39, 49));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        styleButton(loadButton, new Color(61, 91, 118));
        styleButton(previewButton, new Color(63, 91, 84));
        styleButton(traceButton, new Color(112, 79, 52));
        styleButton(cancelButton, new Color(112, 58, 58));
        styleButton(saveButton, new Color(65, 78, 97));
        bar.add(loadButton);
        bar.add(previewButton);
        bar.add(traceButton);
        bar.add(cancelButton);
        bar.add(saveButton);
        loadButton.addActionListener(e -> chooseModel());
        previewButton.addActionListener(e -> renderPreview());
        traceButton.addActionListener(e -> renderRaytrace());
        cancelButton.addActionListener(e -> cancelRender());
        saveButton.addActionListener(e -> saveImage());
        return bar;
    }

    private JPanel buildSidebar() {
        JPanel side = new JPanel(new BorderLayout(8, 12));
        side.setPreferredSize(new Dimension(242, 10));
        side.setBackground(new Color(27, 34, 43));
        side.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 8));

        JLabel title = new JLabel("SCENE SETTINGS");
        title.setForeground(new Color(194, 205, 215));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));

        JPanel top = new JPanel(new BorderLayout(5, 8));
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        modelLabel.setForeground(new Color(133, 153, 170));
        modelLabel.setFont(modelLabel.getFont().deriveFont(Font.PLAIN, 11f));
        modelLabel.setVerticalAlignment(SwingConstants.TOP);
        modelLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 67, 80)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        top.add(modelLabel, BorderLayout.CENTER);

        JPanel settings = new JPanel(new GridLayout(0, 1, 5, 5));
        settings.setOpaque(false);
        settings.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        settings.add(sectionLabel("RAYTRACE QUALITY"));
        qualityBox.setSelectedIndex(2);
        qualityBox.setToolTipText("Samples per pixel. More samples reduce noise but take longer.");
        settings.add(qualityBox);
        settings.add(sectionLabel("OUTPUT SIZE"));
        resolutionBox.setSelectedIndex(2);
        settings.add(resolutionBox);
        settings.add(sectionLabel("ENVIRONMENT"));
        environmentBox.setToolTipText("Ocean mode creates a tessellated, refractive Gerstner-wave water surface.");
        settings.add(environmentBox);
        settings.add(sectionLabel("IMPORTED MODEL SIZE"));
        modelScaleSlider.setToolTipText("Scale imported model from 25% to 300%.");
        settings.add(modelScaleSlider);
        settings.add(sectionLabel("WAVE HEIGHT"));
        waveHeightSlider.setToolTipText("Adjust Gerstner wave amplitude.");
        settings.add(waveHeightSlider);
        settings.add(sectionLabel("WAVE LENGTH"));
        waveLengthSlider.setToolTipText("Adjust Gerstner wave spacing.");
        settings.add(waveLengthSlider);

        JLabel controls = new JLabel("CAMERA CONTROLS");
        controls.setForeground(new Color(194, 205, 215));
        controls.setFont(controls.getFont().deriveFont(Font.BOLD, 11f));
        JLabel help = new JLabel("<html>Left-drag: orbit<br>Mouse wheel: zoom<br><br>Imports: OBJ + MTL + textures.<br>Imported model size and ocean waves are adjustable.<br><br>Long renders can run for hours;<br>use Cancel to stop safely.</html>");
        help.setForeground(new Color(135, 151, 167));
        help.setFont(help.getFont().deriveFont(11f));
        JPanel lower = new JPanel(new BorderLayout(5, 8));
        lower.setOpaque(false);
        lower.add(controls, BorderLayout.NORTH);
        lower.add(help, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(top, BorderLayout.NORTH);
        content.add(settings, BorderLayout.CENTER);
        content.add(lower, BorderLayout.SOUTH);
        side.add(content, BorderLayout.NORTH);
        return side;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(new Color(31, 39, 49));
        bar.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        statusLabel.setForeground(new Color(179, 191, 203));
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        progressBar.setPreferredSize(new Dimension(220, 16));
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setVisible(false);
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(progressBar, BorderLayout.EAST);
        return bar;
    }

    private JLabel sectionLabel(String value) {
        JLabel label = new JLabel(value);
        label.setForeground(new Color(157, 174, 189));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        return label;
    }

    private void styleButton(JButton button, Color color) {
        button.setForeground(Color.WHITE);
        button.setBackground(color);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color.brighter()),
                BorderFactory.createEmptyBorder(7, 11, 7, 11)));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 11f));
    }

    private void installCameraControls() {
        renderPanel.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragStart = e.getPoint(); }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragStart != null && dragStart.distance(e.getPoint()) > 3 && scene != null && !isBusy()) {
                    interactiveTimer.stop();
                    renderInteractivePreview();
                }
                dragStart = null;
            }
        });
        renderPanel.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                int dx = e.getX() - dragStart.x;
                int dy = e.getY() - dragStart.y;
                camera.orbit(dx, dy);
                dragStart = e.getPoint();
                if (scene != null) interactiveTimer.restart();
            }
        });
        renderPanel.addMouseWheelListener((MouseWheelEvent e) -> {
            camera.zoom(e.getPreciseWheelRotation());
            if (scene != null) interactiveTimer.restart();
        });
    }

    private void chooseModel() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select an OBJ model");
        chooser.setFileFilter(new FileNameExtensionFilter("Wavefront OBJ (*.obj)", "obj"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        boolean ocean = environmentBox.getSelectedIndex() == 0;
        setBusy(true, "Loading OBJ mesh, materials and building acceleration structure…", false);
        currentWorker = new SwingWorker<Scene, Void>() {
            @Override protected Scene doInBackground() throws Exception {
                Model model = MeshLoader.load(file);
                return new Scene(model, ocean, modelScale);
            }
            @Override protected void done() {
                try {
                    scene = get();
                    currentModel = scene.model();
                    camera.yaw = 0.75;
                    camera.pitch = 0.24;
                    camera.distance = 6.8;
                    camera.target = new Vec3(0, 1.1, 0);
                    renderPanel.setImage(null);
                    String format = file.getFileName().toString().toLowerCase();
                    modelLabel.setText("<html><b>" + escape(file.getFileName().toString()) + "</b><br>"
                            + currentModel.triangles().size() + " triangles<br>" + format.substring(format.lastIndexOf('.') + 1).toUpperCase()
                            + (ocean ? "<br>Gerstner ocean" : "<br>Studio ground") + "</html>");
                    setIdle("Loaded " + file.getFileName() + " · " + currentModel.triangles().size() + " triangles");
                    renderPreview();
                } catch (CancellationException ignored) {
                    setIdle("Model loading cancelled");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setIdle("Model loading interrupted");
                } catch (ExecutionException ex) {
                    setIdle("Could not load model");
                    showError("Could not load OBJ model", ex.getCause());
                }
            }
        };
        currentWorker.execute();
    }

    private void rebuildEnvironment() {
        if (currentModel == null || isBusy()) return;
        Model model = currentModel;
        boolean ocean = environmentBox.getSelectedIndex() == 0;
        setBusy(true, ocean ? "Generating wave mesh and rebuilding acceleration structure…"
                : "Building studio scene acceleration structure…", false);
        currentWorker = new SwingWorker<Scene, Void>() {
            @Override protected Scene doInBackground() { return new Scene(model, ocean, modelScale); }
            @Override protected void done() {
                try {
                    scene = get();
                    modelLabel.setText("<html><b>" + escape(model.source().getFileName().toString()) + "</b><br>"
                            + model.triangles().size() + " triangles<br>" + (ocean ? "Gerstner ocean" : "Studio ground") + "</html>");
                    setIdle("Environment changed");
                    renderPreview();
                } catch (Exception ex) {
                    setIdle("Environment update failed");
                    showError("Could not rebuild scene", ex);
                }
            }
        };
        currentWorker.execute();
    }

    private void rebuildSceneForControls() {
        if (currentModel == null || isBusy()) return;
        Model model = currentModel;
        boolean ocean = environmentBox.getSelectedIndex() == 0;
        setBusy(true, "Applying model and wave settings…", false);
        currentWorker = new SwingWorker<Scene, Void>() {
            @Override protected Scene doInBackground() {
                return new Scene(model, ocean, modelScale);
            }
            @Override protected void done() {
                try {
                    scene = get();
                    setIdle("Settings applied");
                    renderInteractivePreview();
                } catch (Exception ex) {
                    setIdle("Could not apply settings");
                    showError("Scene update failed", ex);
                }
            }
        };
        currentWorker.execute();
    }

    private void renderInteractivePreview() {
        if (scene == null) return;
        if (isBusy()) { interactiveTimer.restart(); return; }
        CameraSnapshot snapshot = camera.snapshot();
        Scene currentScene = scene;
        int w = Math.min(360, Math.max(240, renderPanel.getWidth() / 3));
        int h = Math.max(180, (int)(w * 9.0 / 16.0));
        setBusy(true, "Interactive low-detail preview…", false);
        currentWorker = new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() {
                return RasterRenderer.render(currentScene, snapshot, w, h, 1);
            }
            @Override protected void done() {
                try {
                    renderPanel.setImage(get());
                    setIdle("Interactive preview · reduced geometry");
                } catch (Exception ex) {
                    setIdle("Interactive preview failed");
                }
            }
        };
        currentWorker.execute();
    }

    private void renderPreview() {
        if (scene == null || isBusy()) return;
        int panelWidth = Math.max(480, renderPanel.getWidth() - 24);
        int panelHeight = Math.max(270, renderPanel.getHeight() - 24);
        int width = Math.min(1100, panelWidth);
        int height = (int) Math.round(width * 9.0 / 16.0);
        if (height > panelHeight) { height = panelHeight; width = (int) Math.round(height * 16.0 / 9.0); }
        final int w = width, h = height;
        CameraSnapshot snapshot = camera.snapshot();
        Scene currentScene = scene;
        setBusy(true, "Rasterizing preview…", false);
        currentWorker = new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() {
                return RasterRenderer.render(currentScene, snapshot, w, h);
            }
            @Override protected void done() {
                try {
                    renderPanel.setImage(get());
                    setIdle("Raster preview · " + w + " × " + h);
                } catch (Exception ex) {
                    setIdle("Preview failed");
                    showError("Preview rendering failed", ex);
                }
            }
        };
        currentWorker.execute();
    }

    private void renderRaytrace() {
        if (scene == null || isBusy()) return;
        int[] resolution = selectedResolution();
        int samples = switch (qualityBox.getSelectedIndex()) {
            case 0 -> 4;
            case 1 -> 16;
            case 3 -> 128;
            case 4 -> 256;
            case 5 -> 512;
            default -> 64;
        };
        int width = resolution[0], height = resolution[1];
        CameraSnapshot snapshot = camera.snapshot();
        Scene currentScene = scene;
        cancelFlag = new AtomicBoolean(false);
        AtomicBoolean cancel = cancelFlag;
        renderStartNanos = System.nanoTime();
        setBusy(true, "Ray tracing · " + width + " × " + height + " · " + samples + " samples/pixel…", true);
        progressBar.setValue(0);
        progressBar.setString("0%");
        currentWorker = new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() {
                PathTracer tracer = new PathTracer(currentScene);
                return tracer.render(snapshot, width, height, samples, (completed, total) -> {
                    int value = (int) Math.round(completed * 100.0 / total);
                    SwingUtilities.invokeLater(() -> {
                        if (currentWorker == this) {
                            progressBar.setValue(value);
                            progressBar.setString(value + "% · " + elapsedText(System.nanoTime() - renderStartNanos));
                        }
                    });
                }, cancel);
            }
            @Override protected void done() {
                try {
                    BufferedImage result = get();
                    String elapsed = elapsedText(System.nanoTime() - renderStartNanos);
                    if (cancel.get()) {
                        setIdle("Ray trace cancelled · elapsed " + elapsed);
                    } else {
                        renderPanel.setImage(result);
                        setIdle("Ray trace complete · " + width + " × " + height + " · " + samples + " spp · " + elapsed);
                    }
                } catch (CancellationException ignored) {
                    setIdle("Ray trace cancelled");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setIdle("Ray trace interrupted");
                } catch (ExecutionException ex) {
                    setIdle("Ray trace failed");
                    showError("Ray tracing failed", ex.getCause());
                }
            }
        };
        currentWorker.execute();
    }

    private int[] selectedResolution() {
        return switch (resolutionBox.getSelectedIndex()) {
            case 0 -> new int[] {640, 360};
            case 1 -> new int[] {960, 540};
            case 2 -> new int[] {1280, 720};
            case 3 -> new int[] {1920, 1080};
            case 4 -> new int[] {2560, 1440};
            default -> new int[] {3840, 2160};
        };
    }

    private void cancelRender() {
        if (cancelFlag != null && isBusy()) {
            cancelFlag.set(true);
            statusLabel.setText("Cancelling render…");
        }
    }

    private void saveImage() {
        BufferedImage image = renderPanel.image();
        if (image == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save rendered image");
        chooser.setSelectedFile(new java.io.File("render.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG image (*.png)", "png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (!path.toString().toLowerCase().endsWith(".png")) path = Path.of(path + ".png");
        try {
            ImageIO.write(image, "png", path.toFile());
            statusLabel.setText("Saved " + path.getFileName());
        } catch (IOException ex) {
            showError("Could not save PNG", ex);
        }
    }

    private void setBusy(boolean busy, String message, boolean showProgress) {
        statusLabel.setText(message);
        progressBar.setVisible(showProgress);
        if (!showProgress) {
            progressBar.setValue(0);
            progressBar.setString("");
        }
        loadButton.setEnabled(!busy);
        previewButton.setEnabled(!busy && scene != null);
        traceButton.setEnabled(!busy && scene != null);
        cancelButton.setEnabled(busy && showProgress && cancelFlag != null);
        saveButton.setEnabled(!busy && renderPanel.image() != null);
        qualityBox.setEnabled(!busy);
        resolutionBox.setEnabled(!busy);
        environmentBox.setEnabled(!busy);
        modelScaleSlider.setEnabled(!busy);
        waveHeightSlider.setEnabled(!busy);
        waveLengthSlider.setEnabled(!busy);
    }

    private void setIdle(String message) {
        statusLabel.setText(message);
        progressBar.setVisible(false);
        progressBar.setValue(0);
        progressBar.setString("");
        updateButtons();
        currentWorker = null;
        cancelFlag = null;
    }

    private boolean isBusy() { return currentWorker != null && !currentWorker.isDone(); }

    private void updateButtons() {
        boolean hasScene = scene != null;
        loadButton.setEnabled(!isBusy());
        previewButton.setEnabled(!isBusy() && hasScene);
        traceButton.setEnabled(!isBusy() && hasScene);
        cancelButton.setEnabled(isBusy() && cancelFlag != null);
        saveButton.setEnabled(!isBusy() && renderPanel.image() != null);
        qualityBox.setEnabled(!isBusy());
        resolutionBox.setEnabled(!isBusy());
        environmentBox.setEnabled(!isBusy());
        modelScaleSlider.setEnabled(!isBusy());
        waveHeightSlider.setEnabled(!isBusy());
        waveLengthSlider.setEnabled(!isBusy());
    }

    private static String elapsedText(long nanos) {
        long seconds = Math.max(0, nanos / 1_000_000_000L);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0 ? String.format("%dh %02dm %02ds", hours, minutes, secs)
                : String.format("%dm %02ds", minutes, secs);
    }

    private void showError(String title, Throwable error) {
        String message = error == null ? "Unknown error" : error.getMessage();
        JOptionPane.showMessageDialog(this, message == null ? error.toString() : message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new Main().setVisible(true);
        });
    }
}
