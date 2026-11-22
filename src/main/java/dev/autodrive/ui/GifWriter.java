package dev.autodrive.ui;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Writes an endlessly looping animated GIF, one frame at a time, using only the JDK's GIF encoder
 * (each frame gets its own 256-colour palette). Frames are scaled by {@code scale} first.
 */
public final class GifWriter implements Closeable {
    private static final String FORMAT = "javax_imageio_gif_image_1.0";

    private final ImageWriter writer;
    private final ImageOutputStream out;
    private final double fps;
    private final double scale;
    private int frames;

    public GifWriter(File file, double fps, double scale) throws IOException {
        this.writer = ImageIO.getImageWritersByFormatName("gif").next();
        file.delete();
        this.out = ImageIO.createImageOutputStream(file);
        this.fps = fps;
        this.scale = scale;
        writer.setOutput(out);
        writer.prepareWriteSequence(null);
    }

    public void add(BufferedImage frame) throws IOException {
        BufferedImage img = scale == 1 ? frame : scaled(frame);
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(FORMAT);

        // GIF delays are in hundredths of a second; spread the rounding so the average matches fps.
        int delay = (int) Math.round((frames + 1) * 100 / fps) - (int) Math.round(frames * 100 / fps);
        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("transparentColorIndex", "0");
        gce.setAttribute("delayTime", Integer.toString(delay));

        if (frames == 0) {                                   // loop forever
            IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[]{1, 0, 0});
            child(root, "ApplicationExtensions").appendChild(loop);
        }
        meta.setFromTree(FORMAT, root);
        writer.writeToSequence(new IIOImage(img, null, meta), param);
        frames++;
    }

    public int frames() {
        return frames;
    }

    @Override
    public void close() throws IOException {
        writer.endWriteSequence();
        out.close();
        writer.dispose();
    }

    private BufferedImage scaled(BufferedImage src) {
        int w = (int) Math.round(src.getWidth() * scale), h = (int) Math.round(src.getHeight() * scale);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
