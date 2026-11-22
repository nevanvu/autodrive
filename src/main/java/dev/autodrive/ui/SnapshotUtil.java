package dev.autodrive.ui;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Saves a JavaFX snapshot as PNG without needing the javafx-swing module. */
public final class SnapshotUtil {
    private SnapshotUtil() {}

    public static void savePng(WritableImage img, File file) throws IOException {
        ImageIO.write(toBufferedImage(img), "png", file);
    }

    public static BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        int[] argb = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, argb, 0, w);
        return out;
    }
}
