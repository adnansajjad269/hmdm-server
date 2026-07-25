package com.hmdm.grafana;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Mints short-lived RS256 JWTs so the Analytics tab's Grafana iframe can log in via Grafana's
 * auth.jwt url_login feature instead of showing Grafana's own login prompt, for Headwind users who
 * already have the "analytics" permission -- see AnalyticsTabController / analytics.html.
 *
 * The RSA key pair is generated once, on first use, under {@code <base.directory>/grafana-jwt/}
 * (the same directory the app already writes uploaded files to, so it's known-writable by whatever
 * user Tomcat runs as -- no new filesystem permission to provision). Only the PUBLIC key needs to
 * leave this process: hmdm-stats' install.sh reads it from the path logged at startup below and
 * copies it into Grafana's own config directory for its [auth.jwt] key_file setting. The private
 * key never leaves this directory.
 *
 * Deliberately fails soft: any problem generating or loading the key pair (unwritable directory,
 * corrupt files, etc.) is logged and leaves this service permanently unavailable for the lifetime
 * of the webapp, rather than throwing out of the constructor -- a Guice singleton that throws during
 * construction can take down the entire application at startup (this exact class of bug already hit
 * this project once via a failed Liquibase changeset), and this feature is a non-essential nicety on
 * top of a tab that already works via Grafana's own login prompt.
 */
@Singleton
public class GrafanaJwtService {

    private static final Logger logger = LoggerFactory.getLogger(GrafanaJwtService.class);
    private static final long TOKEN_VALIDITY_MILLIS = 60_000L; // 60s -- just long enough for the iframe request

    private PrivateKey privateKey;
    private boolean available = false;

    @Inject
    public GrafanaJwtService(@Named("base.directory") String baseDirectory) {
        try {
            if (baseDirectory == null || baseDirectory.isEmpty()) {
                logger.warn("Grafana SSO disabled: base.directory is not configured");
                return;
            }
            Path keyDir = Paths.get(baseDirectory, "grafana-jwt");
            Files.createDirectories(keyDir);
            Path privatePath = keyDir.resolve("private.pem");
            Path publicPath = keyDir.resolve("public.pem");

            if (!Files.exists(privatePath) || !Files.exists(publicPath)) {
                generateAndWriteKeyPair(privatePath, publicPath);
                logger.info("Grafana SSO: generated new RSA key pair. Public key written to: {}", publicPath);
            } else {
                logger.info("Grafana SSO: using existing RSA key pair. Public key at: {}", publicPath);
            }

            this.privateKey = loadPrivateKey(privatePath);
            this.available = true;
        } catch (Exception e) {
            logger.error("Grafana SSO disabled: failed to set up RSA key pair", e);
        }
    }

    /**
     * @return a signed, ~60s-lived JWT with {@code sub} set to the given login, or empty if key
     * setup failed at startup (in which case the Analytics tab falls back to Grafana's own login).
     */
    public Optional<String> mintToken(String login) {
        if (!available) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .setSubject(login)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + TOKEN_VALIDITY_MILLIS))
                .signWith(SignatureAlgorithm.RS256, privateKey)
                .compact();
        return Optional.of(token);
    }

    private void generateAndWriteKeyPair(Path privatePath, Path publicPath) throws Exception {
        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation not available", e);
        }
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        writePem(privatePath, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        writePem(publicPath, "PUBLIC KEY", keyPair.getPublic().getEncoded());

        try {
            Files.setPosixFilePermissions(privatePath, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(publicPath, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows dev box) -- best effort only.
        }
    }

    private void writePem(Path path, String label, byte[] derBytes) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(derBytes);
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(("-----BEGIN " + label + "-----\n").getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < base64.length(); i += 64) {
                out.write(base64.substring(i, Math.min(i + 64, base64.length())).getBytes(StandardCharsets.US_ASCII));
                out.write('\n');
            }
            out.write(("-----END " + label + "-----\n").getBytes(StandardCharsets.US_ASCII));
        }
    }

    private PrivateKey loadPrivateKey(Path path) throws Exception {
        String pem = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII);
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
