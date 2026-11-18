package za.co.oip.documents;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TextExtractorZipTest {

    final TextExtractor extractor = new TextExtractor();

    public static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void extractsSupportedDocumentsFromATenderPack() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("Pack/Invitation to bid.txt", "Bidders must have a CIDB grading of 6CE. Closing date: 4 December 2026."
                .getBytes(StandardCharsets.UTF_8));
        entries.put("Pack/Pricing schedule.csv", "item,qty\nCables,10\n".getBytes(StandardCharsets.UTF_8));
        entries.put("Pack/setup.exe", new byte[]{'M', 'Z', 0, 0, 1, 2, 3});
        entries.put("Pack/inner.zip", zip(Map.of("x.txt", "nested".getBytes(StandardCharsets.UTF_8))));
        entries.put("__MACOSX/._Invitation to bid.txt", "junk".getBytes(StandardCharsets.UTF_8));
        entries.put("Pack/.hidden.txt", "hidden".getBytes(StandardCharsets.UTF_8));

        TextExtractor.Extraction x = extractor.extract(zip(entries), "tender-pack.zip");

        assertThat(x.contentType()).isEqualTo("application/zip");
        assertThat(x.text()).contains("===== Invitation to bid.txt =====", "CIDB grading of 6CE", "===== Pricing schedule.csv =====")
                .contains("Not extracted from the archive", "setup.exe", "inner.zip")
                .doesNotContain("nested", "junk", "hidden");
        // The requirement extractor works on the combined text.
        assertThat(new RequirementExtractor().extract(x.text()).requirements())
                .anyMatch(f -> f.type() == RequirementExtractor.Type.CIDB_GRADING && f.value().equals("6CE"));
    }

    @Test
    void archiveWithoutSupportedDocumentsIsSkipped() throws Exception {
        byte[] data = zip(Map.of("photo.exe", new byte[]{'M', 'Z', 1, 2}));
        assertThatThrownBy(() -> extractor.extract(data, "pack.zip"))
                .isInstanceOf(TextExtractor.UnsupportedDocumentException.class)
                .hasMessageContaining("no supported documents");
    }

    @Test
    void oversizedEntryIsNotRead() throws Exception {
        byte[] big = new byte[(int) TextExtractor.ZIP_MAX_ENTRY_BYTES + 10]; // compresses to almost nothing: a zip bomb in miniature
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("bomb.txt", big);
        entries.put("ok.txt", "Closing date: 4 December 2026".getBytes(StandardCharsets.UTF_8));
        TextExtractor.Extraction x = extractor.extract(zip(entries), "pack.zip");
        assertThat(x.text()).contains("Closing date").contains("bomb.txt (too large)");
    }
}
