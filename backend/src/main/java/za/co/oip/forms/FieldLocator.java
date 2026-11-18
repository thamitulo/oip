package za.co.oip.forms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import za.co.oip.companies.CompanyDirectorService.DirectorForForm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides where each company detail goes on a form: next to (or under) its printed label, into the rows of a
 * directors table (SBD 4), or into real AcroForm fields when the PDF has them. Every result is a proposal the user
 * reviews before a PDF is generated; nothing is ever written over existing printed text.
 */
public final class FieldLocator {

    static final float MARGIN = 30f;
    static final float MIN_INLINE_WIDTH = 50f;
    /** A printed writing line ("______") is an explicit answer space even when short. */
    static final float MIN_LINE_WIDTH = 20f;
    static final float DIRECTOR_ROW_HEIGHT = 18f;
    static final int MAX_DIRECTOR_ROWS = 10;
    /** Labels further into a line than this many words are prose (instructions), not form labels. */
    static final int MAX_WORDS_BEFORE_LABEL = 12;

    private static final Pattern MAAA_PREFIX = Pattern.compile("(?i)^maaa\\d{0,7}$");

    private FieldLocator() {
    }

    public record Result(List<Placement> placements, List<String> warnings) {
    }

    public static Result locate(List<Layout.Page> pages, FillData data) {
        List<Placement> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Layout.Page page : pages) {
            List<Layout.Line> lines = page.lines();
            Set<Integer> claimed = new HashSet<>();
            directorTable(page, lines, data, claimed, out, warnings);
            labels(page, lines, data, claimed, out);
        }
        return new Result(out, warnings);
    }

    // ---------------------------------------------------------------- labels

    private static void labels(Layout.Page page, List<Layout.Line> lines, FillData data, Set<Integer> claimed, List<Placement> out) {
        Set<FormField> done = EnumSet.noneOf(FormField.class);
        for (int li = 0; li < lines.size(); li++) {
            if (claimed.contains(li)) continue;
            Layout.Line line = lines.get(li);
            List<Layout.Word> words = line.words();
            // Line text with the character range of every word, so a regex match maps back to word boxes.
            StringBuilder sb = new StringBuilder();
            int[] wordAt = new int[2048];
            for (int w = 0; w < words.size(); w++) {
                if (w > 0) sb.append(' ');
                for (char ch : words.get(w).text().toLowerCase(Locale.ROOT).toCharArray()) {
                    if (sb.length() < wordAt.length) wordAt[sb.length()] = w;
                    sb.append(ch);
                }
            }
            if (sb.length() >= wordAt.length) continue;
            char[] masked = sb.toString().toCharArray();
            for (FormField f : FormField.values()) {
                if (done.contains(f)) continue;
                for (Pattern p : f.patterns()) {
                    Matcher m = p.matcher(new String(masked));
                    if (!m.find()) continue;
                    int firstWord = wordAt[m.start()];
                    int lastWord = wordAt[Math.max(m.start(), m.end() - 1)];
                    java.util.Arrays.fill(masked, m.start(), m.end(), ' ');
                    if (firstWord > MAX_WORDS_BEFORE_LABEL) break;
                    Placement placed = answerFor(page, lines, li, firstWord, lastWord, f, data);
                    if (placed != null) {
                        out.add(placed);
                        done.add(f);
                    }
                    break;
                }
            }
        }
    }

    /** The empty space for a label's answer: to its right on the same line, otherwise the empty band below. */
    private static Placement answerFor(Layout.Page page, List<Layout.Line> lines, int li, int firstWord, int lastWord,
                                       FormField f, FillData data) {
        Layout.Line line = lines.get(li);
        List<Layout.Word> words = line.words();
        String labelText = String.join(" ", words.subList(firstWord, lastWord + 1).stream().map(Layout.Word::text).toList());
        float labelEnd = words.get(lastWord).x1();
        float start = labelEnd + 4;
        float end = page.width() - MARGIN;
        boolean digitsOnly = false;
        boolean writingLine = false;
        Float nextTextX = null;
        for (int k = lastWord + 1; k < words.size(); k++) {
            Layout.Word w = words.get(k);
            if (f == FormField.CSD_NUMBER && MAAA_PREFIX.matcher(w.text()).matches()) {
                // The form prints "MAAA" itself: write only the digits after it.
                start = w.x1() + 2;
                labelEnd = w.x1();
                digitsOnly = true;
                continue;
            }
            if (w.isFiller()) {
                if (!writingLine && w.text().matches("[_.…]{3,}")) {
                    start = Math.min(start, w.x0());
                    writingLine = true;
                }
                continue;
            }
            nextTextX = w.x0();
            end = w.x0() - 4;
            break;
        }
        // A ruled underline just below the baseline after the label is a writing line too (OCR drops "____" text).
        if (!writingLine) {
            final float le = labelEnd, base = line.y1(), lim = end;
            Layout.Rule underline = page.horizontalRules().stream()
                    .filter(r -> r.y0() >= base - 3 && r.y0() <= base + 5 && r.x0() >= le - 2 && r.x0() <= le + 30 && r.x0() < lim)
                    .min(java.util.Comparator.comparingDouble(Layout.Rule::x0)).orElse(null);
            if (underline != null) {
                start = underline.x0();
                end = Math.min(end, underline.x1());
                writingLine = true;
            }
        }
        // Table cells: answers go in the cell to the right of the label's cell, never across a border.
        if (!writingLine) {
            float cy = (line.y0() + line.y1()) / 2;
            List<Float> borders = page.verticalRules().stream()
                    .filter(r -> r.y0() <= cy && r.y1() >= cy).map(Layout.Rule::x0).sorted().toList();
            Float first = null, second = null;
            for (Float x : borders) {
                if (first == null) {
                    if (x > labelEnd - 1 && x < end) first = x;
                } else if (x > first + 4) {
                    second = x;
                    break;
                }
            }
            if (first != null) {
                float nextCellEnd = second != null ? Math.min(end, second - 3) : end;
                boolean roomInOwnCell = first - 3 - start >= MIN_INLINE_WIDTH;
                boolean roomInNextCell = nextCellEnd - (first + 3) >= MIN_INLINE_WIDTH;
                if (nextTextX != null && roomInOwnCell) {
                    end = first - 3;            // label and answer share a cell; the next cell holds other text
                } else if (roomInNextCell) {
                    start = first + 3;          // | LABEL | answer |
                    end = nextCellEnd;
                } else {
                    end = first - 3;
                }
            }
        }
        String value = data.value(f);
        if (digitsOnly && value != null) value = value.replaceFirst("(?i)^maaa", "");
        double confidence = f.confidence();
        if (end - start >= (writingLine ? MIN_LINE_WIDTH : MIN_INLINE_WIDTH)) {
            float h = Math.max(line.height() + 2, 10);
            return placement(page.index(), start, line.y0() - 1, end - start, h, f.name(), f.label(), value, "LABEL",
                    confidence, labelText);
        }
        // Below: the band between this line and the next one, if nothing is printed there.
        float top = line.y1() + 2;
        float bottom = li + 1 < lines.size() ? lines.get(li + 1).y0() - 2 : page.height() - MARGIN;
        float x = words.get(firstWord).x0();
        if (bottom - top >= 9) {
            float h = Math.min(bottom - top, 28);
            return placement(page.index(), x, top, page.width() - MARGIN - x, h, f.name(), f.label(), value, "LABEL",
                    confidence * 0.8, labelText);
        }
        return null;
    }

    // ---------------------------------------------------------------- directors table

    private static void directorTable(Layout.Page page, List<Layout.Line> lines, FillData data, Set<Integer> claimed,
                                      List<Placement> out, List<String> warnings) {
        for (int li = 0; li < lines.size(); li++) {
            Layout.Line line = lines.get(li);
            String text = String.join(" ", line.words().stream().map(w -> w.text().toLowerCase(Locale.ROOT)).toList());
            if (!text.contains("name") || !(text.contains("identity") || text.matches(".*\\bid (?:number|no\\b).*"))) continue;
            if (line.words().size() > 25) continue;
            Float nameX = null, idX = null, taxX = null, shareX = null;
            for (Layout.Word w : line.words()) {
                String t = w.text().toLowerCase(Locale.ROOT);
                if (nameX == null && (t.startsWith("full") || t.startsWith("name") || t.startsWith("surname"))) nameX = w.x0();
                else if (idX == null && (t.startsWith("identity") || t.equals("id"))) idX = w.x0();
                else if (taxX == null && t.startsWith("tax")) taxX = w.x0();
                else if (shareX == null && (t.startsWith("shar") || t.contains("%") || t.startsWith("percentage"))) shareX = w.x0();
            }
            if (nameX == null || idX == null || idX <= nameX) continue;
            // A header often wraps over a few lines ("Identity" / "number"); rows start below the last of them.
            float headerBottom = line.y1();
            claimed.add(li);
            for (int k = li + 1; k < lines.size() && k <= li + 3; k++) {
                Layout.Line next = lines.get(k);
                if (next.y0() - headerBottom > Math.max(14, line.height() * 1.6)) break;
                if (next.words().getFirst().x0() < nameX - 6) break;
                headerBottom = next.y1();
                claimed.add(k);
            }
            if (data.directors().isEmpty()) {
                warnings.add("Page " + (page.index() + 1) + " has a directors table, but no directors are recorded in your company profile.");
                return;
            }
            List<float[]> cols = new ArrayList<>(); // x start, kind
            cols.add(new float[]{nameX, 0});
            cols.add(new float[]{idX, 1});
            if (taxX != null) cols.add(new float[]{taxX, 2});
            if (shareX != null) cols.add(new float[]{shareX, 3});
            cols.sort((a, b) -> Float.compare(a[0], b[0]));
            // Ruled tables: one row between each pair of horizontal borders under the header. Otherwise fixed rows.
            final float nx = nameX, ix = idX, hb = headerBottom;
            List<Float> borders = page.horizontalRules().stream()
                    .filter(r -> r.x0() <= nx + 6 && r.x1() >= ix && r.y0() >= hb - 8 && r.y0() <= hb + MAX_DIRECTOR_ROWS * 40)
                    .map(Layout.Rule::y0).sorted().toList();
            List<float[]> rowBoxes = new ArrayList<>(); // top, height
            for (int b = 0; b + 1 < borders.size(); b++) {
                float h = borders.get(b + 1) - borders.get(b);
                if (h >= 9 && h <= 60) rowBoxes.add(new float[]{borders.get(b) + 2, h - 4});
                else if (h > 60) break;
            }
            boolean ruled = rowBoxes.size() >= Math.min(2, data.directors().size());
            int rows = Math.min(data.directors().size(), MAX_DIRECTOR_ROWS);
            for (int r = 0; r < rows; r++) {
                DirectorForForm d = data.directors().get(r);
                if (ruled && r >= rowBoxes.size()) {
                    warnings.add("Page " + (page.index() + 1) + ": the directors table has " + rowBoxes.size()
                            + " rows; add the remaining directors on an annexure.");
                    break;
                }
                float y = ruled ? rowBoxes.get(r)[0] : headerBottom + 4 + r * DIRECTOR_ROW_HEIGHT;
                float rowHeight = ruled ? Math.max(8, rowBoxes.get(r)[1]) : DIRECTOR_ROW_HEIGHT - 4;
                if (!ruled && y + DIRECTOR_ROW_HEIGHT > page.height() - MARGIN) {
                    warnings.add("Page " + (page.index() + 1) + ": not all directors fit in the table; add the rest by hand.");
                    break;
                }
                for (int c = 0; c < cols.size(); c++) {
                    float x0 = cols.get(c)[0];
                    float x1 = c + 1 < cols.size() ? cols.get(c + 1)[0] - 4 : page.width() - MARGIN;
                    int kind = (int) cols.get(c)[1];
                    String field;
                    String label;
                    String value;
                    switch (kind) {
                        case 0 -> { field = "DIRECTOR_NAME"; label = "Director name"; value = d.fullName(); }
                        case 1 -> {
                            field = "DIRECTOR_ID";
                            label = "Director identity number";
                            value = data.idNumbersWithheld() ? null : d.idNumberMasked();
                        }
                        case 2 -> { field = "DIRECTOR_TAX"; label = "Director tax number"; value = d.incomeTaxNumber(); }
                        default -> {
                            field = "DIRECTOR_SHARE";
                            label = "Shareholding";
                            value = d.shareholdingPercentage() == null ? null : d.shareholdingPercentage().stripTrailingZeros().toPlainString() + "%";
                        }
                    }
                    String labelText = "Directors table, row " + (r + 1);
                    if (kind == 1 && data.idNumbersWithheld()) labelText += " (identity numbers need profile edit rights)";
                    out.add(placement(page.index(), x0, y, Math.max(20, x1 - x0), rowHeight, field, label,
                            value, "TABLE", 0.5, labelText).withRef(d.id().toString()));
                }
            }
            return; // one directors table per page
        }
    }

    // ---------------------------------------------------------------- AcroForm

    /** Text fields of a fillable PDF whose name or tooltip matches a company detail. */
    public static List<Placement> acroForm(PDDocument doc, FillData data) throws IOException {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        List<Placement> out = new ArrayList<>();
        if (form == null) return out;
        Set<FormField> done = EnumSet.noneOf(FormField.class);
        for (PDField field : form.getFieldTree()) {
            if (!(field instanceof PDTextField text) || text.isReadOnly()) continue;
            String name = text.getAlternateFieldName() != null && !text.getAlternateFieldName().isBlank()
                    ? text.getAlternateFieldName() : text.getPartialName();
            if (name == null) continue;
            String normalised = name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[_\\-.]+", " ")
                    .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            FormField match = null;
            for (FormField f : FormField.values()) {
                if (done.contains(f)) continue;
                if (f.patterns().stream().anyMatch(p -> p.matcher(normalised).find())) {
                    match = f;
                    break;
                }
            }
            if (match == null || text.getWidgets().isEmpty()) continue;
            PDAnnotationWidget widget = text.getWidgets().getFirst();
            int pageIndex = pageOf(doc, widget);
            if (pageIndex < 0) continue;
            PDRectangle crop = doc.getPage(pageIndex).getCropBox();
            PDRectangle r = widget.getRectangle();
            float x = r.getLowerLeftX() - crop.getLowerLeftX();
            float y = crop.getUpperRightY() - r.getUpperRightY();
            Placement p = placement(pageIndex, Math.max(0, x), Math.max(0, y), Math.max(1, r.getWidth()),
                    Math.max(4, Math.min(200, r.getHeight())), match.name(), match.label(), data.value(match), "ACROFORM", 0.95, name);
            out.add(new Placement(p.id(), p.page(), p.x(), p.y(), p.width(), p.height(), p.fontSize(), p.field(), p.fieldLabel(),
                    p.value(), p.source(), p.confidence(), p.labelText(), text.getFullyQualifiedName(), p.missing(), null));
            done.add(match);
        }
        return out;
    }

    private static int pageOf(PDDocument doc, PDAnnotationWidget widget) throws IOException {
        if (widget.getPage() != null) {
            int i = doc.getPages().indexOf(widget.getPage());
            if (i >= 0) return i;
        }
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            for (var a : page.getAnnotations()) {
                if (a.getCOSObject() == widget.getCOSObject()) return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- helpers

    static Placement placement(int page, float x, float y, float width, float height, String field, String fieldLabel,
                               String value, String source, double confidence, String labelText) {
        float font = Math.max(6, Math.min(10, height * 0.75f));
        boolean missing = value == null || value.isBlank();
        return new Placement(UUID.randomUUID().toString(), page, Math.max(0, x), Math.max(0, y), Math.max(1, width),
                Math.max(4, Math.min(200, height)), font, field, fieldLabel, missing ? "" : value, source,
                Math.round(confidence * 100) / 100.0, labelText, null, missing, null);
    }
}
