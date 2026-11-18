package za.co.oip.forms;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Writes reviewed values onto a copy of the form and renders page previews. The original is never modified. */
public final class FormRenderer {

    static final float MIN_FONT = 6f;

    private FormRenderer() {
    }

    public static PDDocument load(byte[] pdf) throws IOException {
        PDDocument doc = Loader.loadPDF(pdf);
        if (doc.isEncrypted()) {
            // Owner-password-only PDFs (common for tender packs) open fine; remove the restriction on our copy.
            doc.setAllSecurityToBeRemoved(true);
        }
        return doc;
    }

    /** The filled PDF. Empty values are skipped. */
    public static byte[] fill(byte[] original, List<Placement> placements) throws IOException {
        try (PDDocument doc = load(original)) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDAcroForm acro = doc.getDocumentCatalog().getAcroForm();
            List<Placement> drawn = new ArrayList<>();
            for (Placement p : placements) {
                if (p.value() == null || p.value().isBlank() || p.page() >= doc.getNumberOfPages()) continue;
                if ("ACROFORM".equals(p.source()) && acro != null && p.acroField() != null) {
                    PDField f = acro.getField(p.acroField());
                    if (f != null) {
                        try {
                            f.setValue(p.value());
                            continue;
                        } catch (IOException | IllegalArgumentException e) {
                            // No usable appearance font in the form: draw the text instead.
                        }
                    }
                }
                drawn.add(p);
            }
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                final int pageIndex = i;
                List<Placement> onPage = drawn.stream().filter(p -> p.page() == pageIndex).toList();
                if (onPage.isEmpty()) continue;
                PDPage page = doc.getPage(i);
                PDRectangle crop = page.getCropBox();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(0.05f, 0.1f, 0.45f); // dark blue, like a pen: clearly an entry, still formal
                    for (Placement p : onPage) {
                        draw(cs, font, crop, p);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void draw(PDPageContentStream cs, PDType1Font font, PDRectangle crop, Placement p) throws IOException {
        String text = encodable(font, p.value().replaceAll("\\s+", " ").trim());
        float size = p.fontSize();
        while (size > MIN_FONT && width(font, text, size) > p.width()) size -= 0.5f;
        List<String> lines = width(font, text, size) <= p.width() ? List.of(text) : wrap(font, text, size, p.width());
        float leading = size * 1.15f;
        int maxLines = Math.max(1, (int) (p.height() / leading));
        if (lines.size() > maxLines) lines = lines.subList(0, maxLines);
        float blockHeight = lines.size() * leading;
        // Vertically centre in the box; PDF y grows upwards from the crop box's lower edge.
        float firstBaselineFromTop = p.y() + Math.max(0, (p.height() - blockHeight) / 2) + size * 0.85f;
        for (int i = 0; i < lines.size(); i++) {
            float x = crop.getLowerLeftX() + p.x() + 1;
            float y = crop.getUpperRightY() - (firstBaselineFromTop + i * leading);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(lines.get(i));
            cs.endText();
        }
    }

    static List<String> wrap(PDType1Font font, String text, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = cur.isEmpty() ? word : cur + " " + word;
            if (width(font, candidate, size) <= maxWidth || cur.isEmpty()) {
                cur = new StringBuilder(candidate);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    private static float width(PDType1Font font, String s, float size) throws IOException {
        return font.getStringWidth(s) / 1000f * size;
    }

    /** Replaces characters the standard Helvetica encoding cannot show (WinAnsi) with '?'. */
    static String encodable(PDType1Font font, String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    /** A PNG preview of one page. */
    public static byte[] preview(byte[] pdf, int pageIndex, float dpi) throws IOException {
        try (PDDocument doc = load(pdf)) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) throw new IllegalArgumentException("No such page");
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            image.flush();
            return out.toByteArray();
        }
    }
}
