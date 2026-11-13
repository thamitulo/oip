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
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv");

    static final String ZIP = "application/zip";
    /** Limits for tender packs delivered as ZIP files (protect against zip bombs). */
    static final int ZIP_MAX_ENTRIES = 60;
    static final long ZIP_MAX_ENTRY_BYTES = 40L * 1024 * 1024;
    static final long ZIP_MAX_TOTAL_BYTES = 200L * 1024 * 1024;

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
        if (ZIP.equals(type)) {
            return extractZip(data);
        }
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

    /**
     * Extracts the supported documents inside a ZIP pack (one level; nested archives are skipped), each preceded by a
     * header line with its name. Entries are read in memory within the size limits; nothing is written to disk.
     */
    Extraction extractZip(byte[] data) {
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        int pages = 0;
        boolean anyPages = false;
        int used = 0;
        long total = 0;
        java.util.List<String> skipped = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(data))) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (e.isDirectory() || base.isEmpty() || base.startsWith(".") || name.startsWith("__MACOSX/")) continue;
                if (used >= ZIP_MAX_ENTRIES) { skipped.add(base + " (too many files)"); continue; }
                byte[] entry = zip.readNBytes((int) ZIP_MAX_ENTRY_BYTES + 1);
                total += entry.length;
                if (entry.length > ZIP_MAX_ENTRY_BYTES) { skipped.add(base + " (too large)"); continue; }
                if (total > ZIP_MAX_TOTAL_BYTES) { skipped.add(base + " (archive too large)"); break; }
                String type;
                try {
                    type = detect(entry, base);
                } catch (UnsupportedDocumentException ex) {
                    skipped.add(base);
                    continue;
                }
                if (!ALLOWED_TYPES.contains(type)) { skipped.add(base); continue; }
                used++;
                try {
                    Extraction inner = extract(entry, base);
                    text.append("\n\n===== ").append(base).append(" =====\n").append(inner.text());
                    truncated |= inner.truncated();
                    if (inner.pageCount() != null) { pages += inner.pageCount(); anyPages = true; }
                } catch (RuntimeException ex) {
                    skipped.add(base + " (could not be read)");
                }
                if (text.length() >= MAX_CHARS) { truncated = true; break; }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read ZIP archive: " + e.getMessage(), e);
        }
        if (used == 0) {
            throw new UnsupportedDocumentException("ZIP archive contains no supported documents"
                    + (skipped.isEmpty() ? "" : " (" + String.join(", ", skipped.subList(0, Math.min(5, skipped.size()))) + ")"));
        }
        if (!skipped.isEmpty()) {
            text.append("\n\n[Not extracted from the archive: ")
                    .append(String.join(", ", skipped.subList(0, Math.min(20, skipped.size()))))
                    .append(skipped.size() > 20 ? " and " + (skipped.size() - 20) + " more" : "").append("]");
        }
        String out = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text.toString().strip();
        return new Extraction(ZIP, out, truncated, anyPages ? pages : null);
    }

    private static boolean isWriteLimit(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("WriteLimit")) return true;
        }
        return false;
    }
}
