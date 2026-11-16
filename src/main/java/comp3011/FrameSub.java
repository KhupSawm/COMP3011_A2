/*
 * Author: Khup Sawm, a1924824
 */

package comp3011;


import org.bytedeco.javacv.Frame;

import java.util.Objects;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;


// Draws speech to text subtitles onto each frame while enabled
// Stub for now as a placeholder will work on the command line option first
public class FrameSub extends FrameProcessor {
	
	// True while subtitle should be drawn. Written on the FX thread, read on the effect thread.
	private volatile boolean enabled = true;
	
	// Temp subtitile source for testing.
	private volatile SubtitleSource subtitleSource = new StubSubtitleSource();
	
	
    private static final int MAX_CHARS_PER_LINE = 42;
    
    // Store the last subtitle image so we don't recreate it on every frame.
    private SubtitleCue renderedCue;
    private SubtitleSource renderedSource;
    private int[] overlayPixels;
    private int overlayTop;
    private int overlayHeight;
	
	public boolean isEnabled() {
		return enabled;
	}
	
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
	
    public void setSubtitleSource(SubtitleSource subtitleSource) {
        this.subtitleSource = Objects.requireNonNull(subtitleSource);
    }
	
	@Override
	public void process(Frame frame, InfoFrame info) throws Exception {
		if (!enabled || frame.image == null || frame.image.length == 0
                || frame.image[0] == null) {
			return;
		}
		
		SubtitleSource source = subtitleSource;

        // Clear the old cached subtitle if a different source is supplied.
        if (source != renderedSource) {
            renderedSource = source;
            renderedCue = null;
            overlayPixels = null;
        }

        // Find the subtitle that matches the current video timestamp.
        SubtitleCue cue = source.cueAt(info.timestampUs());

        // Remove the subtitle when there is no text for this timestamp.
        if (cue == null) {
            renderedCue = null;
            overlayPixels = null;
            return;
        }

        // Only create a new image when the subtitle text changes.
        if (!cue.equals(renderedCue)) {
            createOverlay(cue);
            renderedCue = cue;
        }

        // Draw the cached subtitle image onto the current video frame.
        if (overlayPixels != null) {
            drawOverlay(frame);
        }

	}
	// Creates an image containing the subtitle text and its black outline.
    private void createOverlay(SubtitleCue cue) {

        List<String> lines = makeLines(cue.lines());

        if (lines.isEmpty()) {
            overlayPixels = null;
            return;
        }

        int width = videoInfo.imageWidth();
        int imageHeight = videoInfo.imageHeight();

        // Choose a font size based on video height.
        int fontSize = Math.max(14, Math.min(48, imageHeight / 18));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);

        // A small temporary image lets us measure the subtitle text.
        BufferedImage measureImage =
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        Graphics2D measureGraphics = measureImage.createGraphics();
        FontMetrics metrics;

        // Reduce the font size if the longest line is too wide.
        while (true) {
            measureGraphics.setFont(font);
            metrics = measureGraphics.getFontMetrics();

            int longestLine = 0;

            // Find the width of the longest subtitle line.
            for (String line : lines) {
                longestLine = Math.max(
                        longestLine, metrics.stringWidth(line));
            }

            // Stop reducing the font when the text fits.
            if (longestLine <= width * 0.90 || fontSize <= 14) {
                break;
            }

            fontSize--;
            font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        }

        measureGraphics.dispose();

        int outlineWidth = Math.max(2, fontSize / 10);
        int lineGap = Math.max(2, fontSize / 5);
        int padding = outlineWidth + 4;

        // Calculate the height needed for all subtitle lines.
        overlayHeight = metrics.getHeight() * lines.size()
                + lineGap * (lines.size() - 1) + padding * 2;

        BufferedImage overlay = new BufferedImage(
                width, overlayHeight, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = overlay.createGraphics();

        // Make the text edges smoother.
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        graphics.setFont(font);

        // Set the thickness of the black outline.
        graphics.setStroke(new BasicStroke(
                outlineWidth,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));

        int baseline = padding + metrics.getAscent();

        // Draw each subtitle line in the centre of the image.
        for (String line : lines) {

            int x = (width -
                    graphics.getFontMetrics().stringWidth(line)) / 2;

            GlyphVector glyphs =
                    font.createGlyphVector(
                            graphics.getFontRenderContext(), line);

            Shape textShape = glyphs.getOutline(x, baseline);

            // Draw the black outline first.
            graphics.setColor(Color.BLACK);
            graphics.draw(textShape);

            // Draw the white text over the outline.
            graphics.setColor(Color.WHITE);
            graphics.fill(textShape);

            // Move down for the next subtitle line.
            baseline += metrics.getHeight() + lineGap;
        }

        graphics.dispose();

        // Store the completed image as pixels for the video frame.
        overlayPixels = overlay.getRGB(
                0, 0, width, overlayHeight, null, 0, width);

        // Position the subtitles near the bottom of the video.
        int margin = Math.max(8, imageHeight / 30);

        overlayTop = Math.max(
                0, imageHeight - overlayHeight - margin);
    }

    // Splits subtitle text into a maximum of two lines.
    private List<String> makeLines(List<String> subtitleLines) {

        String text = String.join(" ", subtitleLines).trim();
        List<String> lines = new ArrayList<>();

        // Continue until the text is used or two lines have been created.
        while (!text.isEmpty() && lines.size() < 2) {

            // Keep the remaining text together if it already fits.
            if (text.length() <= MAX_CHARS_PER_LINE) {
                lines.add(text);
                break;
            }

            // Try to break at a space rather than splitting a word.
            int splitAt = text.lastIndexOf(' ', MAX_CHARS_PER_LINE);

            // If there is no suitable space, split at 42 characters.
            if (splitAt <= 0) {
                splitAt = MAX_CHARS_PER_LINE;
            } else {

                int punctuationBreak = -1;

                // Prefer a punctuation mark before the normal split point.
                for (int i = 0; i < splitAt; i++) {
                    char character = text.charAt(i);

                    if (character == '.' || character == ','
                            || character == '!' || character == '?'
                            || character == ';' || character == ':') {

                        punctuationBreak = i + 1;
                    }
                }

                // Use the punctuation position when one is available.
                if (punctuationBreak > 0) {
                    splitAt = punctuationBreak;
                }
            }

            // Save the first part and continue with the remaining text.
            lines.add(text.substring(0, splitAt).trim());
            text = text.substring(splitAt).trim();
        }

        // Use an ellipsis when some text cannot fit within two lines.
        if (!text.isEmpty() && !lines.isEmpty()) {

            int lastIndex = lines.size() - 1;
            String lastLine = lines.get(lastIndex);

            if (lastLine.length() > MAX_CHARS_PER_LINE - 3) {
                lastLine = lastLine.substring(
                        0, MAX_CHARS_PER_LINE - 3).trim();
            }

            lines.set(lastIndex, lastLine + "...");
        }

        return lines;
    }

    // Copies the subtitle pixels onto the video frame.
    private void drawOverlay(Frame frame) throws Exception {

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception(
                    "FrameSub requires byte-backed image frames");
        }

        // The overlay code works with standard 8-bit image frames.
        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE
                && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception(
                    "FrameSub requires 8-bit image frames");
        }

        int width = videoInfo.imageWidth();
        int height = videoInfo.imageHeight();
        int stride = videoInfo.imageStride();
        int channels = videoInfo.imageChannels();

        // Leave the alpha channel unchanged for two- or four-channel images.
        int colourChannels =
                channels == 2 ? 1 : Math.min(channels, 3);

        // Visit each row of the subtitle image.
        for (int y = 0; y < overlayHeight; y++) {

            int frameY = overlayTop + y;

            // Stop if the subtitle goes beyond the bottom of the video.
            if (frameY >= height) {
                break;
            }

            int frameRow = frameY * stride;
            int overlayRow = y * width;

            // Visit each pixel in the current subtitle row.
            for (int x = 0; x < width; x++) {

                int argb = overlayPixels[overlayRow + x];

                // Read the transparency value of this subtitle pixel.
                int alpha = (argb >>> 24) & 0xff;

                // Skip transparent pixels so the original video stays visible.
                if (alpha == 0) {
                    continue;
                }

                // The subtitle is white and its outline is black.
                // The same brightness value is used for each colour channel.
                int grey = (
                        ((argb >>> 16) & 0xff)
                        + ((argb >>> 8) & 0xff)
                        + (argb & 0xff)) / 3;

                int base = frameRow + x * channels;

                // Avoid writing outside the video frame's image buffer.
                if (base + channels > pixels.limit()) {
                    continue;
                }

                // Blend the subtitle with the original video pixel.
                for (int channel = 0;
                        channel < colourChannels; channel++) {

                    int original = pixels.get(base + channel) & 0xff;

                    // Alpha blending keeps the edges of the text smooth.
                    int blended =
                            (grey * alpha
                            + original * (255 - alpha)) / 255;

                    pixels.put(base + channel, (byte) blended);
                }
            }
        }
     }

}
