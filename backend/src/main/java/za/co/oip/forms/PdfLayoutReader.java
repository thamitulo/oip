package za.co.oip.forms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the words and their positions on each page: from the text layer for digital PDFs, and with Tesseract OCR
 * for scanned pages (pages with almost no text). Pages are rendered one at a time to keep memory bounded.
 */
@Component
public class PdfLayoutReader {

    /** Fewer text characters than this means the page is a scan. */
    static final int SCANNED_PAGE_CHARS = 20;
    /** Resolution for finding table rules on digital pages. */
    static final int RULES_DPI = 100;

    public record Result(List<Layout.Page> pages, int ocrPages, List<String> warnings) {
    }

    private final TesseractOcr ocr;
    private final OcrProperties ocrProperties;

    public PdfLayoutReader(TesseractOcr ocr, OcrProperties ocrProperties) {
        this.ocr = ocr;
        this.ocrProperties = ocrProperties;
    }

    public Result read(PDDocument doc) throws IOException {
        List<Layout.Page> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int ocrPages = 0;
        PDFRenderer renderer = null;
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            PDRectangle box = page.getCropBox();
            if (page.getRotation() % 360 != 0) {
                warnings.add("Page " + (i + 1) + " is rotated; its fields were not detected. Add them by hand if needed.");
                pages.add(new Layout.Page(i, box.getWidth(), box.getHeight(), List.of(), false));
                continue;
            }
            List<Layout.Word> words = textWords(doc, i);
            int chars = words.stream().mapToInt(w -> w.text().length()).sum();
            boolean viaOcr = false;
            if (renderer == null) renderer = new PDFRenderer(doc);
            RuleDetector.Rules rules = RuleDetector.Rules.NONE;
            if (chars < SCANNED_PAGE_CHARS) {
                if (!ocr.isAvailable()) {
                    warnings.add("Page " + (i + 1) + " is scanned and OCR is not available on this server.");
                } else if (ocrPages >= ocrProperties.maxPages()) {
                    warnings.add("Page " + (i + 1) + " is scanned; only the first " + ocrProperties.maxPages() + " scanned pages are read.");
                } else {
                    BufferedImage image = renderer.renderImageWithDPI(i, ocr.dpi(), ImageType.GRAY);
                    try {
                        rules = RuleDetector.detect(image, 72f / ocr.dpi());
                        words = ocr.read(image);
                        viaOcr = true;
                        ocrPages++;
                    } catch (IllegalStateException e) {
                        warnings.add("Page " + (i + 1) + " could not be read by OCR: " + e.getMessage());
                    } finally {
                        image.flush();
                    }
                }
            }
            if (!viaOcr && !words.isEmpty()) {
                BufferedImage image = renderer.renderImageWithDPI(i, RULES_DPI, ImageType.GRAY);
                rules = RuleDetector.detect(image, 72f / RULES_DPI);
                image.flush();
            }
            pages.add(new Layout.Page(i, box.getWidth(), box.getHeight(), words, viaOcr, rules.vertical(), rules.horizontal()));
        }
        return new Result(pages, ocrPages, warnings);
    }

    /** Words from the text layer of one page, positioned relative to the crop box's top-left corner. */
    static List<Layout.Word> textWords(PDDocument doc, int pageIndex) throws IOException {
        List<Layout.Word> words = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                List<TextPosition> current = new ArrayList<>();
                TextPosition prev = null;
                for (TextPosition t : positions) {
                    boolean blank = t.getUnicode() == null || t.getUnicode().isBlank();
                    boolean gap = prev != null
                            && t.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj()) > Math.max(1.0f, t.getWidthOfSpace() * 0.5f);
                    if (blank || gap) {
                        flush(current, words);
                        current = new ArrayList<>();
                    }
                    if (!blank) current.add(t);
                    prev = t;
                }
                flush(current, words);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);
        return words;
    }

    private static void flush(List<TextPosition> chars, List<Layout.Word> out) {
        if (chars.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        float x0 = Float.MAX_VALUE, x1 = 0, y0 = Float.MAX_VALUE, y1 = 0;
        for (TextPosition t : chars) {
            sb.append(t.getUnicode());
            float h = Math.max(t.getHeightDir(), t.getFontSizeInPt() * 0.7f);
            x0 = Math.min(x0, t.getXDirAdj());
            x1 = Math.max(x1, t.getXDirAdj() + t.getWidthDirAdj());
            y0 = Math.min(y0, t.getYDirAdj() - h);
            y1 = Math.max(y1, t.getYDirAdj());
        }
        out.add(new Layout.Word(sb.toString(), x0, y0, x1, y1));
    }
}
