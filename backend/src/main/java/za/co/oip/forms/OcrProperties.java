package za.co.oip.forms;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OCR for scanned form pages ({@code oip.ocr.*}).
 *
 * @param enabled  use OCR when a page has no text layer
 * @param command  the Tesseract executable
 * @param language Tesseract language pack(s), e.g. {@code eng}
 * @param dpi      render resolution for OCR; 300 is Tesseract's sweet spot
 * @param timeout  per page
 * @param maxPages scanned pages read per document (protects memory and CPU)
 */
@ConfigurationProperties(prefix = "oip.ocr")
public record OcrProperties(boolean enabled, String command, String language, int dpi, Duration timeout, int maxPages) {

    public OcrProperties {
        if (command == null || command.isBlank()) command = "tesseract";
        if (language == null || language.isBlank()) language = "eng";
        if (dpi <= 0) dpi = 300;
        if (timeout == null) timeout = Duration.ofSeconds(60);
        if (maxPages <= 0) maxPages = 30;
    }
}
