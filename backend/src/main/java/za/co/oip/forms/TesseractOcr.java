package za.co.oip.forms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the Tesseract command line on a rendered page and returns words with their boxes (TSV output). The image is
 * written to a private temp file that is always deleted; nothing leaves the server.
 */
@Component
public class TesseractOcr {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcr.class);
    private static final int MIN_CONFIDENCE = 30;

    private final OcrProperties properties;
    private volatile Boolean available;

    public TesseractOcr(OcrProperties properties) {
        this.properties = properties;
    }

    /** Whether OCR is enabled and the Tesseract binary runs. Checked once. */
    public boolean isAvailable() {
        if (!properties.enabled()) return false;
        if (available == null) {
            try {
                Process p = new ProcessBuilder(properties.command(), "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                available = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (IOException e) {
                available = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!available) log.info("Tesseract is not available ({}); scanned form pages will not be read", properties.command());
        }
        return available;
    }

    public int dpi() {
        return properties.dpi();
    }

    /** Words found on the image, in points (72 per inch) from the top-left. */
    public List<Layout.Word> read(BufferedImage image) {
        Path png = null;
        try {
            png = Files.createTempFile("oip-ocr-", ".png");
            ImageIO.write(image, "png", png.toFile());
            Process p = new ProcessBuilder(properties.command(), png.toString(), "stdout", "-l", properties.language(),
                    "--psm", "3", "tsv").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String tsv = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("OCR timed out");
            }
            if (p.exitValue() != 0) throw new IllegalStateException("OCR failed (exit " + p.exitValue() + ")");
            return parseTsv(tsv, 72f / properties.dpi());
        } catch (IOException e) {
            throw new IllegalStateException("OCR failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR interrupted", e);
        } finally {
            if (png != null) {
                try {
                    Files.deleteIfExists(png);
                } catch (IOException ignored) {
                    // temp file cleanup is best effort
                }
            }
        }
    }

    /** Parses Tesseract TSV (level, page, block, par, line, word, left, top, width, height, conf, text). */
    static List<Layout.Word> parseTsv(String tsv, float scale) {
        List<Layout.Word> words = new ArrayList<>();
        for (String row : tsv.split("\\R")) {
            String[] c = row.split("\t", -1);
            if (c.length < 12 || !"5".equals(c[0])) continue;
            String text = c[11].trim();
            double conf;
            try {
                conf = Double.parseDouble(c[10]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (text.isEmpty() || conf < MIN_CONFIDENCE) continue;
            int left = Integer.parseInt(c[6]), top = Integer.parseInt(c[7]), w = Integer.parseInt(c[8]), h = Integer.parseInt(c[9]);
            words.add(new Layout.Word(text, left * scale, top * scale, (left + w) * scale, (top + h) * scale));
        }
        return words;
    }
}
