package za.co.oip.crawling.http;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Uses the JDK's default trust manager for every host, except hosts with a reviewed missing intermediate
 * certificate (see {@link CrawlerTrustProperties}). For those it uses a standard PKIX trust manager over the same
 * root CAs, with the intermediate available for path building. Hostname verification still happens: the
 * delegate is always called with the TLS socket/engine.
 */
public final class HostScopedTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager defaultTrust;
    private final Map<String, X509ExtendedTrustManager> byHost;

    HostScopedTrustManager(X509ExtendedTrustManager defaultTrust, Map<String, X509ExtendedTrustManager> byHost) {
        this.defaultTrust = defaultTrust;
        this.byHost = Map.copyOf(byHost);
    }

    /** A TLS context for the crawler; the JDK default when no intermediates are configured. */
    public static SSLContext sslContext(List<LoadedIntermediate> intermediates) throws GeneralSecurityException {
        if (intermediates.isEmpty()) {
            return SSLContext.getDefault();
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{create(intermediates)}, null);
        return ctx;
    }

    public record LoadedIntermediate(Set<String> hosts, X509Certificate certificate) {
    }

    static HostScopedTrustManager create(List<LoadedIntermediate> intermediates) throws GeneralSecurityException {
        X509ExtendedTrustManager defaultTrust = defaultTrustManager();
        Set<TrustAnchor> anchors = Arrays.stream(defaultTrust.getAcceptedIssuers())
                .map(c -> new TrustAnchor(c, null)).collect(Collectors.toSet());
        Map<String, X509ExtendedTrustManager> byHost = new HashMap<>();
        for (LoadedIntermediate i : intermediates) {
            PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, new X509CertSelector());
            params.setRevocationEnabled(false); // same as the JDK default trust manager
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(List.of(i.certificate()))));
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
            tmf.init(new CertPathTrustManagerParameters(params));
            X509ExtendedTrustManager tm = Arrays.stream(tmf.getTrustManagers())
                    .filter(X509ExtendedTrustManager.class::isInstance).map(X509ExtendedTrustManager.class::cast)
                    .findFirst().orElseThrow();
            i.hosts().forEach(h -> byHost.put(h.toLowerCase(Locale.ROOT), tm));
        }
        return new HostScopedTrustManager(defaultTrust, byHost);
    }

    static X509ExtendedTrustManager defaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return Arrays.stream(tmf.getTrustManagers()).filter(X509ExtendedTrustManager.class::isInstance)
                .map(X509ExtendedTrustManager.class::cast).findFirst().orElseThrow();
    }

    /** Reads a PEM certificate and checks it against the expected SHA-256 fingerprint. */
    public static X509Certificate load(InputStream pem, String expectedSha256) throws GeneralSecurityException {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        String expected = expectedSha256 == null ? "" : expectedSha256.replace(":", "").toLowerCase(Locale.ROOT);
        if (!actual.equals(expected)) {
            throw new CertificateException("Fingerprint mismatch for " + cert.getSubjectX500Principal() + ": expected "
                    + expected + ", got " + actual);
        }
        if (cert.getBasicConstraints() < 0) {
            throw new CertificateException(cert.getSubjectX500Principal() + " is not a CA certificate");
        }
        return cert;
    }

    X509ExtendedTrustManager forHost(String host) {
        return host == null ? defaultTrust : byHost.getOrDefault(host.toLowerCase(Locale.ROOT), defaultTrust);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        String host = socket instanceof SSLSocket s && s.getHandshakeSession() != null ? s.getHandshakeSession().getPeerHost() : null;
        forHost(host).checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        forHost(engine == null ? null : engine.getPeerHost()).checkServerTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        defaultTrust.checkServerTrusted(chain, authType); // no host known: no extra intermediates
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        defaultTrust.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        defaultTrust.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        defaultTrust.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return defaultTrust.getAcceptedIssuers();
    }
}
