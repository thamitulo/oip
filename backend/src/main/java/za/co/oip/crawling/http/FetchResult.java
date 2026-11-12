package za.co.oip.crawling.http;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A successful (2xx) HTTP response. */
public record FetchResult(URI uri, int status, String contentType, byte[] body, Instant fetchedAt, String sha256) {

    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);

    public String text() {
        return new String(body, charset());
    }

    public Charset charset() {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    public boolean isContentType(String type) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(type);
    }
}
