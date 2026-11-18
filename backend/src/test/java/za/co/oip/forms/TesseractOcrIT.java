package za.co.oip.forms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCR path on a real scan: the SBD 1 test form is rendered to an image-only PDF (no text layer), read with
 * Tesseract and filled. Runs only where Tesseract is installed (the backend Docker image has it).
 */
@EnabledIf("tesseractInstalled")
class TesseractOcrIT {

    static final OcrProperties PROPS = new OcrProperties(true, "tesseract", "eng", 300, Duration.ofSeconds(60), 5);

    static boolean tesseractInstalled() {
        return new TesseractOcr(PROPS).isAvailable();
    }

    /** The digital form, printed and scanned: one image per page, nothing else. */
    static byte[] scanned(byte[] digital) throws Exception {
        try (PDDocument src = FormRenderer.load(digital); PDDocument out = new PDDocument()) {
            BufferedImage img = new PDFRenderer(src).renderImageWithDPI(0, 200, ImageType.GRAY);
            PDPage page = new PDPage(PDRectangle.A4);
            out.addPage(page);
            PDImageXObject x = LosslessFactory.createFromImage(out, img);
            try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return bytes.toByteArray();
        }
    }

    @Test
    void readsAndFillsAScannedForm() throws Exception {
        byte[] scan = scanned(FieldLocatorTest.pdf(FieldLocatorTest.SBD1));
        TesseractOcr ocr = new TesseractOcr(PROPS);
        try (PDDocument doc = FormRenderer.load(scan)) {
            assertThat(PdfLayoutReader.textWords(doc, 0)).isEmpty(); // really a scan
            PdfLayoutReader.Result layout = new PdfLayoutReader(ocr, PROPS).read(doc);
            assertThat(layout.ocrPages()).isEqualTo(1);
            List<Placement> ps = FieldLocator.locate(layout.pages(), FieldLocatorTest.data(false)).placements();
            assertThat(ps).extracting(Placement::field).contains("COMPANY_NAME", "VAT_NUMBER", "CSD_NUMBER", "REGISTRATION_NUMBER");
            Placement vat = ps.stream().filter(p -> p.field().equals("VAT_NUMBER")).findFirst().orElseThrow();
            assertThat(vat.y()).isBetween(145f, 165f); // the label sits at y≈160 in the original
            assertThat(vat.x()).isGreaterThan(150f);
        }
    }
}
