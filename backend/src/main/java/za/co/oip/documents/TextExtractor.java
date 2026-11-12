package za.co.oip.documents;

import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts plain text from documents with Apache Tika. Only an allow-list of formats is accepted; the content
 * type is detected from the bytes (not trusted from the server or file name). Macros and embedded executables
 * are never run: Tika only reads document structure, and embedded documents are not recursed into.
 */
@Component
public class TextExtractor {

    public static final int MAX_CHARS = 2_000_000;

    static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/x-tika-ooxml",
            "application/x-tika-msoffice",
            "text/plain",
            "text/csv");

    public record Extraction(String contentType, String text, boolean truncated, Integer pageCount) {
    }

    public static class UnsupportedDocumentException extends RuntimeException {
        public UnsupportedDocumentException(String message) {
            super(message);
        }
    }

    private final AutoDetectParser parser = new AutoDetectParser();
    private final DefaultDetector detector = new DefaultDetector();

    public String detect(byte[] data, String fileName) {
        Metadata metadata = new Metadata();
        if (fileName != null) {
            metadata.set("resourceName", fileName);
        }
        try (TikaInputStream in = TikaInputStream.get(new ByteArrayInputStream(data))) {
            MediaType type = detector.detect(in, metadata, new ParseContext());
            return type.getBaseType().toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new UnsupportedDocumentException("Could not detect document type");
        }
    }

    public Extraction extract(byte[] data, String fileName) {
        String type = detect(data, fileName);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new UnsupportedDocumentException("Unsupported document type: " + type);
        }
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        Metadata metadata = new Metadata();
        boolean truncated = false;
        try (TikaInputStream in = TikaInputStream.get(new ByteArrayInputStream(data))) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            if (isWriteLimit(e)) {
                truncated = true;
            } else {
                throw new IllegalStateException("Text extraction failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
            }
        }
        String text = handler.toString().replace('\u0000', ' ');
        Integer pages = null;
        String pageValue = metadata.get("xmpTPg:NPages");
        if (pageValue != null) {
            try {
                pages = Integer.parseInt(pageValue);
            } catch (NumberFormatException ignored) {
                // leave null
            }
        }
        return new Extraction(type, text, truncated, pages);
    }

    private static boolean isWriteLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("WriteLimit")) return true;
        }
        return false;
    }
}
