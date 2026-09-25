package com.openclaim.engine.outbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.ingest.ClaimIngestionService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payer requires a client certificate. Key material is generated per run with the JDK's keytool, so nothing
 * secret is committed. The negative test proves the stub really enforces client auth, which keeps the positive test
 * from passing vacuously.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PayerMutualTlsIT {

    private static final String PASSWORD = "test-only";
    private static final Path DIR = generateStores();
    private static final WireMockServer payer = new WireMockServer(wireMockConfig()
            .httpDisabled(true)
            .dynamicHttpsPort()
            .keystoreType("PKCS12")
            .keystorePath(DIR.resolve("server.p12").toString())
            .keystorePassword(PASSWORD)
            .keyManagerPassword(PASSWORD)
            .needClientAuth(true)
            .trustStoreType("PKCS12")
            .trustStorePath(DIR.resolve("server-trust.p12").toString())
            .trustStorePassword(PASSWORD));

    static {
        payer.start();
        payer.stubFor(post("/claims").willReturn(aResponse().withStatus(202)));
    }

    @AfterAll
    static void stop() {
        payer.stop();
    }

    @DynamicPropertySource
    static void mutualTls(DynamicPropertyRegistry registry) {
        registry.add("OPENCLAIM_PAYER_BASE_URL", () -> "https://localhost:" + payer.httpsPort());
        registry.add("openclaim.payer.ssl-bundle", () -> "payer");
        registry.add("spring.ssl.bundle.jks.payer.keystore.location", () -> "file:" + DIR.resolve("client.p12"));
        registry.add("spring.ssl.bundle.jks.payer.keystore.password", () -> PASSWORD);
        registry.add("spring.ssl.bundle.jks.payer.keystore.type", () -> "PKCS12");
        registry.add(
                "spring.ssl.bundle.jks.payer.truststore.location", () -> "file:" + DIR.resolve("client-trust.p12"));
        registry.add("spring.ssl.bundle.jks.payer.truststore.password", () -> PASSWORD);
        registry.add("spring.ssl.bundle.jks.payer.truststore.type", () -> "PKCS12");
    }

    @Autowired
    PayerGatewayClient client;

    @Autowired
    OutboxRelay relay;

    @Autowired
    ClaimIngestionService ingestion;

    @Autowired
    ClaimRepository claims;

    @Test
    void deliversOverMutualTls() {
        Claim claim = ingestion.submit(aSubmission().build(), id -> "{}").claim();

        while (relay.runOnce() > 0) {
            // drain
        }

        assertThat(claims.findById(claim.id()).orElseThrow().status()).isEqualTo(ClaimStatus.DISPATCHED);
    }

    @Test
    void payerStubRejectsClientsWithoutACertificate() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(DIR.resolve("client-trust.p12"))) {
            trust.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext serverTrustOnly = SSLContext.getInstance("TLS");
        serverTrustOnly.init(null, trustManagers.getTrustManagers(), null);

        try (HttpClient anonymous =
                HttpClient.newBuilder().sslContext(serverTrustOnly).build()) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://localhost:" + payer.httpsPort() + "/claims"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            assertThatThrownBy(() -> anonymous.send(request, HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(IOException.class);
        }
    }

    private static Path generateStores() {
        try {
            Path dir = Files.createTempDirectory("openclaim-mtls");
            dir.toFile().deleteOnExit();
            for (String party : List.of("server", "client")) {
                String cn = party.equals("server") ? "localhost" : "openclaim-core";
                keytool(
                        dir,
                        "-genkeypair",
                        "-alias",
                        party,
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=" + cn,
                        "-ext",
                        "SAN=dns:localhost,ip:127.0.0.1",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        party + ".p12",
                        "-storepass",
                        PASSWORD,
                        "-keypass",
                        PASSWORD);
                keytool(
                        dir,
                        "-exportcert",
                        "-alias",
                        party,
                        "-keystore",
                        party + ".p12",
                        "-storepass",
                        PASSWORD,
                        "-file",
                        party + ".crt");
            }
            keytool(
                    dir,
                    "-importcert",
                    "-noprompt",
                    "-alias",
                    "server",
                    "-file",
                    "server.crt",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    "client-trust.p12",
                    "-storepass",
                    PASSWORD);
            keytool(
                    dir,
                    "-importcert",
                    "-noprompt",
                    "-alias",
                    "client",
                    "-file",
                    "client.crt",
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    "server-trust.p12",
                    "-storepass",
                    PASSWORD);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate test key stores", e);
        }
    }

    private static void keytool(Path dir, String... args) throws IOException {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool " + args[0] + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
