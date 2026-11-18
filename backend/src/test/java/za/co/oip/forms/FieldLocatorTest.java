package za.co.oip.forms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import za.co.oip.companies.CompanyDirectorService.DirectorForForm;
import za.co.oip.companies.domain.CompanyDirector.Role;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Field detection on generated forms shaped like SBD 1 and SBD 4. All company data here is fake. */
class FieldLocatorTest {

    static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    /** Text lines at (x, y-from-top). */
    record Text(float x, float y, String s) {
    }

    static byte[] pdf(List<Text> texts) throws Exception {
        return pdf(texts, List.of());
    }

    /** Boxes are table cells: x, y-from-top, width, height. */
    static byte[] pdf(List<Text> texts, List<float[]> boxes) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.6f);
                for (float[] b : boxes) {
                    cs.addRect(b[0], PDRectangle.A4.getHeight() - b[1] - b[3], b[2], b[3]);
                    cs.stroke();
                }
                for (Text t : texts) {
                    cs.beginText();
                    cs.setFont(FONT, 9);
                    cs.newLineAtOffset(t.x(), PDRectangle.A4.getHeight() - t.y());
                    cs.showText(t.s());
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    static final List<Text> SBD1 = List.of(
            new Text(40, 60, "SBD 1 PART A INVITATION TO BID"),
            new Text(40, 100, "NAME OF BIDDER"),
            new Text(40, 120, "POSTAL ADDRESS"),
            new Text(40, 140, "TELEPHONE NUMBER ______________________"),
            new Text(40, 160, "VAT REGISTRATION NUMBER"),
            new Text(40, 180, "SUPPLIER COMPLIANCE STATUS  CENTRAL SUPPLIER DATABASE No: MAAA"),
            new Text(40, 200, "COMPANY REGISTRATION NUMBER ......................   INCOME TAX REFERENCE NUMBER ......................"),
            new Text(40, 240, "Bidders must ensure that the VAT registration number and the central supplier database number are"),
            new Text(40, 252, "valid on the closing date, failing which the bid will be disqualified without further notice."));

    static FillData data(boolean withheld) {
        Map<FormField, String> v = new EnumMap<>(FormField.class);
        v.put(FormField.COMPANY_NAME, "Example Holdings (Pty) Ltd");
        v.put(FormField.POSTAL_ADDRESS, "PO Box 1, Sandton, 2146");
        v.put(FormField.CONTACT_PHONE, "011 000 0000");
        v.put(FormField.VAT_NUMBER, "4123456789");
        v.put(FormField.CSD_NUMBER, "MAAA0001234");
        v.put(FormField.REGISTRATION_NUMBER, "2019/123456/07");
        v.put(FormField.INCOME_TAX_NUMBER, "9123456789");
        List<DirectorForForm> directors = List.of(
                new DirectorForForm(UUID.randomUUID(), "Thandi Example", Role.DIRECTOR, null, "•••••••••9087", "9000000001",
                        new BigDecimal("60"), false),
                new DirectorForForm(UUID.randomUUID(), "Sipho Example", Role.DIRECTOR, null, "•••••••••3088", null,
                        new BigDecimal("40.00"), false));
        return new FillData(v, directors, withheld);
    }

    static List<Placement> locate(byte[] pdf, FillData data) throws Exception {
        try (PDDocument doc = FormRenderer.load(pdf)) {
            List<Layout.Page> pages = List.of(new Layout.Page(0, 595, 842, PdfLayoutReader.textWords(doc, 0), false));
            return FieldLocator.locate(pages, data).placements();
        }
    }

    static Optional<Placement> find(List<Placement> ps, String field) {
        return ps.stream().filter(p -> p.field().equals(field)).findFirst();
    }

    @Test
    void findsSbd1FieldsWithoutConfusingSimilarLabels() throws Exception {
        List<Placement> ps = locate(pdf(SBD1), data(false));

        assertThat(find(ps, "COMPANY_NAME")).get().satisfies(p -> {
            assertThat(p.value()).isEqualTo("Example Holdings (Pty) Ltd");
            assertThat(p.x()).isGreaterThan(100); // to the right of the label
            assertThat(p.y()).isBetween(85f, 105f);
        });
        assertThat(find(ps, "VAT_NUMBER")).get().extracting(Placement::value).isEqualTo("4123456789");
        // The form prints "MAAA": only the digits are written, after it.
        assertThat(find(ps, "CSD_NUMBER")).get().satisfies(p -> assertThat(p.value()).isEqualTo("0001234"));
        // Two labels on one line: each answer stops before the next label.
        Placement reg = find(ps, "REGISTRATION_NUMBER").orElseThrow();
        Placement tax = find(ps, "INCOME_TAX_NUMBER").orElseThrow();
        assertThat(reg.value()).isEqualTo("2019/123456/07");
        assertThat(reg.x() + reg.width()).isLessThan(tax.x());
        // "VAT registration number" is never taken as the company registration number.
        assertThat(ps.stream().filter(p -> p.field().equals("REGISTRATION_NUMBER"))).hasSize(1);
        // Labels inside instruction prose do not produce extra placements.
        assertThat(ps.stream().filter(p -> p.y() > 230)).isEmpty();
    }

    @Test
    void writesValuesAndKeepsTheOriginalText() throws Exception {
        byte[] original = pdf(SBD1);
        List<Placement> ps = locate(original, data(false));
        byte[] filled = FormRenderer.fill(original, ps);
        try (PDDocument doc = FormRenderer.load(filled)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("NAME OF BIDDER", "Example Holdings (Pty) Ltd", "4123456789", "MAAA", "0001234");
        }
    }

    @Test
    void missingProfileValuesAreFlaggedNotInvented() throws Exception {
        FillData empty = new FillData(Map.of(), List.of(), false);
        List<Placement> ps = locate(pdf(SBD1), empty);
        assertThat(find(ps, "VAT_NUMBER")).get().satisfies(p -> {
            assertThat(p.missing()).isTrue();
            assertThat(p.value()).isEmpty();
        });
    }

    @Test
    void fillsOneRowPerDirectorInAnSbd4Table() throws Exception {
        byte[] sbd4 = pdf(List.of(
                new Text(40, 60, "SBD 4 BIDDER'S DISCLOSURE"),
                new Text(40, 100, "Full Name          Identity Number          Tax Number          Shareholding %"),
                new Text(40, 300, "DECLARATION")));
        List<Placement> ps = locate(sbd4, data(false));
        List<Placement> names = ps.stream().filter(p -> p.field().equals("DIRECTOR_NAME")).toList();
        assertThat(names).extracting(Placement::value).containsExactly("Thandi Example", "Sipho Example");
        assertThat(names.get(1).y()).isGreaterThan(names.get(0).y());
        assertThat(ps.stream().filter(p -> p.field().equals("DIRECTOR_ID")).map(Placement::value))
                .containsExactly("•••••••••9087", "•••••••••3088"); // masked in drafts; real numbers only at generation
        assertThat(ps.stream().filter(p -> p.field().equals("DIRECTOR_ID"))).allSatisfy(p -> assertThat(p.ref()).isNotNull());
        assertThat(ps.stream().filter(p -> p.field().equals("DIRECTOR_SHARE")).map(Placement::value)).containsExactly("60%", "40%");
        // The header's "Tax Number" is part of the table, not the company's tax number field.
        assertThat(find(ps, "INCOME_TAX_NUMBER")).isEmpty();
    }

    /** Layout with table rules, as the service reads it (OCR switched off). */
    static List<Placement> locateWithRules(byte[] pdf, FillData data) throws Exception {
        OcrProperties off = new OcrProperties(false, null, null, 0, null, 0);
        try (PDDocument doc = FormRenderer.load(pdf)) {
            return FieldLocator.locate(new PdfLayoutReader(new TesseractOcr(off), off).read(doc).pages(), data).placements();
        }
    }

    @Test
    void answersGoInTheCellRightOfTheLabelNotAcrossItsBorder() throws Exception {
        // | NAME OF BIDDER | (answer) |  and  | VAT REGISTRATION NUMBER | (answer) |, label cells end at x=230.
        List<float[]> cells = List.of(new float[]{36, 88, 194, 18}, new float[]{230, 88, 330, 18},
                new float[]{36, 106, 194, 18}, new float[]{230, 106, 330, 18});
        byte[] form = pdf(List.of(new Text(40, 100, "NAME OF BIDDER"), new Text(40, 118, "VAT REGISTRATION NUMBER")), cells);
        List<Placement> ps = locateWithRules(form, data(false));
        assertThat(find(ps, "COMPANY_NAME")).get().satisfies(p -> {
            assertThat(p.x()).isBetween(230f, 240f);
            assertThat(p.x() + p.width()).isLessThanOrEqualTo(565f);
        });
        assertThat(find(ps, "VAT_NUMBER")).get().satisfies(p -> assertThat(p.x()).isBetween(230f, 240f));
    }

    @Test
    void directorRowsFollowTheTableRules() throws Exception {
        // Header at y≈100; ruled rows of 24pt starting at y=106.
        List<float[]> rows = List.of(new float[]{36, 106, 520, 24}, new float[]{36, 130, 520, 24}, new float[]{36, 154, 520, 24});
        byte[] form = pdf(List.of(new Text(40, 100, "Full Name          Identity Number          Shareholding %")), rows);
        List<Placement> names = locateWithRules(form, data(false)).stream().filter(p -> p.field().equals("DIRECTOR_NAME")).toList();
        assertThat(names).hasSize(2);
        assertThat(names.get(0).y()).isBetween(106f, 112f);
        assertThat(names.get(1).y()).isBetween(130f, 136f);
    }

    @Test
    void withheldIdNumbersAreLeftEmptyAndFlagged() throws Exception {
        byte[] sbd4 = pdf(List.of(new Text(40, 100, "Full Name          Identity Number          Shareholding %")));
        List<Placement> ids = locate(sbd4, data(true)).stream().filter(p -> p.field().equals("DIRECTOR_ID")).toList();
        assertThat(ids).hasSize(2).allSatisfy(p -> {
            assertThat(p.value()).isEmpty();
            assertThat(p.labelText()).contains("profile edit rights");
        });
    }

    @Test
    void fillsRealAcroFormFields() throws Exception {
        byte[] form;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDAcroForm acro = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acro);
            acro.setDefaultAppearance("/Helv 0 Tf 0 g");
            acro.getDefaultResources();
            var res = new org.apache.pdfbox.pdmodel.PDResources();
            res.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), FONT);
            acro.setDefaultResources(res);
            PDTextField vat = new PDTextField(acro);
            vat.setPartialName("vatRegistrationNumber");
            PDAnnotationWidget w = vat.getWidgets().getFirst();
            w.setRectangle(new PDRectangle(200, 700, 150, 16));
            w.setPage(page);
            page.getAnnotations().add(w);
            acro.getFields().add(vat);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            form = out.toByteArray();
        }
        List<Placement> ps;
        try (PDDocument doc = FormRenderer.load(form)) {
            ps = FieldLocator.acroForm(doc, data(false));
        }
        assertThat(ps).singleElement().satisfies(p -> {
            assertThat(p.field()).isEqualTo("VAT_NUMBER");
            assertThat(p.source()).isEqualTo("ACROFORM");
            assertThat(p.y()).isBetween(120f, 130f); // 842 - 716
        });
        try (PDDocument doc = FormRenderer.load(FormRenderer.fill(form, ps))) {
            assertThat(doc.getDocumentCatalog().getAcroForm().getField("vatRegistrationNumber").getValueAsString()).isEqualTo("4123456789");
        }
    }

    @Test
    void parsesTesseractTsv() {
        String tsv = """
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                5\t1\t1\t1\t1\t1\t300\t600\t120\t40\t91.5\tVAT
                5\t1\t1\t1\t1\t2\t430\t600\t100\t40\t12\tnoise
                """;
        List<Layout.Word> words = TesseractOcr.parseTsv(tsv, 72f / 300);
        assertThat(words).singleElement().satisfies(w -> {
            assertThat(w.text()).isEqualTo("VAT");
            assertThat(w.x0()).isEqualTo(72f);
            assertThat(w.y0()).isEqualTo(144f);
        });
    }
}
