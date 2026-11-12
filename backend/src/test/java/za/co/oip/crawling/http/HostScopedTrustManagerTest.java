package za.co.oip.crawling.http;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HostScopedTrustManagerTest {

    static final String FINGERPRINT = "6542D176BED50F193C0CE297AE44ECD8A0A86BEC2EDE682769344059B4E78530";
    static final String PEM = "crawler-trust/sectigo-public-server-authentication-ca-ov-r36.pem";

    /** The certificates www.dlrrd.gov.za sent on 2026-11-12: its own, but not the CA that issued it. */
    static X509Certificate[] servedChain() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("src/test/resources/fixtures/tls/dlrrd-served-chain-2026-11-12.pem"))) {
            return CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                    .map(X509Certificate.class::cast).toArray(X509Certificate[]::new);
        }
    }

    static X509Certificate intermediate() throws Exception {
        try (InputStream in = new DefaultResourceLoader().getResource("classpath:" + PEM).getInputStream()) {
            return HostScopedTrustManager.load(in, FINGERPRINT);
        }
    }

    @Test
    void missingIntermediateIsSuppliedOnlyForTheConfiguredHost() throws Exception {
        X509Certificate[] chain = servedChain();
        assumeTrue(chain[0].getNotAfter().after(new Date()), "fixture certificate has expired; refresh the fixture");
        var tm = HostScopedTrustManager.create(List.of(
                new HostScopedTrustManager.LoadedIntermediate(Set.of("www.dlrrd.gov.za", "dlrrd.gov.za"), intermediate())));

        // The JDK default (and any other host) rejects the incomplete chain...
        assertThatThrownBy(() -> tm.checkServerTrusted(chain, "RSA")).isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> tm.forHost("www.example.org").checkServerTrusted(chain, "RSA")).isInstanceOf(CertificateException.class);
        // ...and only the configured host gets the reviewed intermediate.
        assertThatCode(() -> tm.forHost("WWW.DLRRD.GOV.ZA").checkServerTrusted(chain, "RSA")).doesNotThrowAnyException();
    }

    @Test
    void certificateWithWrongFingerprintIsRejected() throws Exception {
        byte[] pem = new DefaultResourceLoader().getResource("classpath:" + PEM).getInputStream().readAllBytes();
        assertThatThrownBy(() -> HostScopedTrustManager.load(new ByteArrayInputStream(pem), "00".repeat(32)))
                .isInstanceOf(CertificateException.class).hasMessageContaining("Fingerprint mismatch");
    }

    @Test
    void configuredSslContextLoadsFromApplicationConfig() {
        var trust = new CrawlerTrustProperties(List.of(new CrawlerTrustProperties.Intermediate(
                List.of("www.dlrrd.gov.za"), "classpath:" + PEM, FINGERPRINT, "test")));
        assertThatCode(() -> PoliteHttpClient.sslContext(trust, new DefaultResourceLoader())).doesNotThrowAnyException();
        var wrong = new CrawlerTrustProperties(List.of(new CrawlerTrustProperties.Intermediate(
                List.of("www.dlrrd.gov.za"), "classpath:" + PEM, "ab".repeat(32), "test")));
        assertThatThrownBy(() -> PoliteHttpClient.sslContext(wrong, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
    }
}
