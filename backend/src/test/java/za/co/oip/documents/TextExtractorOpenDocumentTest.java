package za.co.oip.documents;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** OpenDocument files are ZIP containers; they must be read as documents, not unpacked as tender packs. */
class TextExtractorOpenDocumentTest {

    final TextExtractor extractor = new TextExtractor();

    /** A minimal valid OpenDocument package: an uncompressed "mimetype" entry first, then the manifest and content. */
    static byte[] odf(String mimeType, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            byte[] mt = mimeType.getBytes(StandardCharsets.US_ASCII);
            ZipEntry first = new ZipEntry("mimetype");
            first.setMethod(ZipEntry.STORED);
            first.setSize(mt.length);
            CRC32 crc = new CRC32();
            crc.update(mt);
            first.setCrc(crc.getValue());
            z.putNextEntry(first);
            z.write(mt);
            z.closeEntry();
            for (var e : Map.of(
                    "META-INF/manifest.xml", """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                             <manifest:file-entry manifest:full-path="/" manifest:media-type="%s"/>
                             <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                            </manifest:manifest>""".formatted(mimeType),
                    "content.xml", """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                              xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
                             <office:body><office:text><text:p>%s</text:p></office:text></office:body>
                            </office:document-content>""".formatted(body)).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void readsOpenDocumentText() throws Exception {
        byte[] odt = odf("application/vnd.oasis.opendocument.text", "Bidders must hold a CIDB grading of 5GB or higher.");
        TextExtractor.Extraction x = extractor.extract(odt, "bid-form.odt");
        assertThat(x.contentType()).isEqualTo("application/vnd.oasis.opendocument.text");
        assertThat(x.text()).contains("CIDB grading of 5GB");
    }

    @Test
    void readsOpenDocumentInsideATenderPack() throws Exception {
        byte[] odt = odf("application/vnd.oasis.opendocument.text", "Compulsory briefing session on 3 December 2026.");
        byte[] pack = TextExtractorZipTest.zip(Map.of("Pack/Briefing.odt", odt));
        TextExtractor.Extraction x = extractor.extract(pack, "pack.zip");
        assertThat(x.contentType()).isEqualTo("application/zip");
        assertThat(x.text()).contains("===== Briefing.odt =====", "Compulsory briefing session");
    }
}
