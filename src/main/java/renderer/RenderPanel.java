package renderer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class RenderPanel extends JPanel {
    private BufferedImage image;

    RenderPanel() {
        setBackground(new Color(15, 20, 27));
        setOpaque(true);
        setPreferredSize(new java.awt.Dimension(960, 600));
        setToolTipText("Drag to orbit the camera. Use the mouse wheel to zoom.");
    }

    void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    BufferedImage image() { return image; }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (image != null) {
            double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int w = (int) (image.getWidth() * scale);
            int h = (int) (image.getHeight() * scale);
            int x = (getWidth() - w) / 2;
            int y = (getHeight() - h) / 2;
            g2.drawImage(image, x, y, w, h, null);
            g2.setColor(new Color(185, 198, 210, 80));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(x, y, w - 1, h - 1);
        } else {
            g2.setColor(new Color(190, 200, 211));
            g2.setFont(getFont().deriveFont(Font.BOLD, 20f));
            String title = "WARSHIP STUDIO";
            int tw = g2.getFontMetrics().stringWidth(title);
            g2.drawString(title, Math.max(20, (getWidth() - tw) / 2), getHeight() / 2 - 26);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 13f));
            g2.setColor(new Color(132, 146, 160));
            String line = "Load a 3D model to begin";
            int lw = g2.getFontMetrics().stringWidth(line);
            g2.drawString(line, Math.max(20, (getWidth() - lw) / 2), getHeight() / 2 + 4);
            String help = "Drag to orbit  ·  Scroll to zoom  ·  Texture  ·  Render";
            int hw = g2.getFontMetrics().stringWidth(help);
            g2.drawString(help, Math.max(20, (getWidth() - hw) / 2), getHeight() / 2 + 30);
        }
        g2.dispose();
    }
}
