package samples.emulator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;

/**
 * A connection to a running AWS Emulator, and the one place where the samples' AWS clients are
 * pointed at it.
 *
 * <h2>What the emulator gives you</h2>
 * When AWS Emulator starts, it writes a few files into {@code ~/.aws/emulator/<port>/}:
 * <ul>
 *   <li>{@code emulator_credentials}: an access key and secret key that work only with this run of
 *       the emulator. By default the emulator checks that every request is signed with them (SigV4),
 *       exactly like AWS checks your real keys.</li>
 *   <li>{@code ca.pem}: the certificate it serves HTTPS with. It is new on every start, so we read it
 *       on every connect instead of installing it anywhere.</li>
 *   <li>{@code truststore.p12}: the same certificate as a Java trust store (not used here; see below).</li>
 *   <li>{@code config}: an AWS config file with the endpoint and Region (the AWS CLI can use it).</li>
 * </ul>
 * The emulator deletes these files when it stops cleanly (Ctrl+C, SIGTERM or {@code --idle-timeout}).
 * This class reads them, so the samples run from IntelliJ with no environment variables to set.
 *
 * <p>Why trust {@code ca.pem} in the SDK's HTTP client rather than point the whole JVM at
 * {@code truststore.p12} ({@code -Djavax.net.ssl.trustStore=...}, the emulator guide's way for Java)?
 * A JVM-wide trust store <i>replaces</i> the default one, so every other HTTPS call in the program
 * would fail. Trusting the certificate only in the clients built here leaves the rest of the JVM alone.
 *
 * <h2>Pointing an SDK client at the emulator</h2>
 * An AWS SDK client needs four things to talk to the emulator instead of AWS: the emulator's
 * <b>endpoint</b>, a <b>Region</b>, the emulator's <b>credentials</b>, and an <b>HTTP client that trusts
 * its certificate</b>. {@link #client} applies all four to any service's client builder (plus one
 * safety setting, no host prefixes, explained in the method):
 * <pre>{@code
 * try (Emulator emulator = Emulator.connect();
 *      S3Client s3 = emulator.client(S3Client.builder())) {
 *     s3.listBuckets();   // goes to the emulator, never to AWS
 * }
 * }</pre>
 * Everything after that is ordinary AWS SDK code: the same calls you would make against AWS.
 *
 * <p>Outside Java you would set environment variables instead ({@code AWS_ENDPOINT_URL},
 * {@code AWS_SHARED_CREDENTIALS_FILE}, {@code AWS_CA_BUNDLE}, ...); see README.md.
 */
public final class Emulator implements AutoCloseable {

    /** The port the emulator listens on unless you start it with {@code -p}. */
    public static final int DEFAULT_PORT = 9297;

    /**
     * The emulator accepts any Region and keeps most resources apart by Region, but not all of them
     * (CloudWatch Logs log groups are shared; see sample 11), so use one Region for everything (one
     * emulator per Region if you must test several). us-east-1 is
     * also what its own {@code config} file names.
     */
    public static final Region REGION = Region.US_EAST_1;

    /** The single account every emulator acts as. */
    public static final String ACCOUNT_ID = "000000000000";

    /**
     * Any keys will do for an emulator started with {@code --auth off}: it does not check signatures,
     * but the SDK still signs, and the signature tells the emulator which service a request is for.
     */
    private static final AwsCredentialsProvider ANY_KEYS_FOR_AUTH_OFF =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("auth-off-any-key", "auth-off-any-secret"));

    private final int port;
    private final Path dir;
    private final URI endpoint;
    private final TrustManager[] trustManagers;
    private final AwsCredentialsProvider credentials;
    private final SdkHttpClient httpClient;

    private Emulator(int port) {
        this.port = port;
        this.dir = Path.of(System.getProperty("user.home"), ".aws", "emulator", Integer.toString(port));
        // Use 127.0.0.1, not "localhost": with an IP address the SDKs put S3 bucket names in the path,
        // which is what the emulator expects (see the emulator guide, "Host names that your SDK changes").
        this.endpoint = URI.create("https://127.0.0.1:" + port);
        Path caFile = dir.resolve("ca.pem");
        Path credentialsFile = dir.resolve("emulator_credentials");
        if (!Files.exists(caFile)) {
            throw new IllegalStateException("""
                    No AWS Emulator is running on port %d (no ca.pem in %s).
                    Start one in a terminal first:   aws-emulator%s
                    (or set AWS_EMULATOR_PORT / -Demulator.port to the port of the one you started)"""
                    .formatted(port, dir, port == DEFAULT_PORT ? "" : " -p " + port));
        }
        this.trustManagers = trustOnly(caFile);
        // No credentials file means the emulator was started with --auth off.
        this.credentials = Files.exists(credentialsFile) ? credentialsFrom(credentialsFile) : ANY_KEYS_FOR_AUTH_OFF;
        this.httpClient = ApacheHttpClient.builder()
                .tlsTrustManagersProvider(() -> trustManagers)
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Connects to the emulator on the port named by the {@code emulator.port} system property, the
     * {@code AWS_EMULATOR_PORT} environment variable, or {@value #DEFAULT_PORT}.
     *
     * @throws IllegalStateException if no emulator is running there, with a hint on how to start one
     */
    public static Emulator connect() {
        String configured = System.getProperty("emulator.port", System.getenv("AWS_EMULATOR_PORT"));
        Emulator emulator = at(configured == null || configured.isBlank() ? DEFAULT_PORT : Integer.parseInt(configured.strip()));
        if (!emulator.isHealthy()) {
            emulator.close();
            throw new IllegalStateException("AWS Emulator on port " + emulator.port + " does not answer its health check."
                    + " If it crashed, start it again.");
        }
        return emulator;
    }

    /** Connects to the emulator on {@code port}. */
    public static Emulator at(int port) {
        return new Emulator(port);
    }

    /**
     * Points an AWS SDK client builder at this emulator and builds the client. Works for any
     * service: {@code emulator.client(DynamoDbClient.builder())}, {@code emulator.client(SqsClient.builder())},
     * and so on. Set anything service-specific on the builder before you pass it in.
     */
    public <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C client(B builder) {
        return client(builder, credentials);
    }

    /**
     * Like {@link #client(AwsClientBuilder)}, but signing with other credentials. Only for showing what
     * the emulator does with keys it did not issue (sample 7); normally use {@link #client(AwsClientBuilder)}.
     */
    public <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C client(
            B builder, AwsCredentialsProvider signingCredentials) {
        return client(builder, signingCredentials, REGION);
    }

    /**
     * Like {@link #client(AwsClientBuilder)}, but for another Region. Only for showing how the emulator
     * treats Regions (sample 11); normally use one Region throughout, {@link #REGION}.
     */
    public <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C client(B builder, Region region) {
        return client(builder, credentials, region);
    }

    private <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> C client(
            B builder, AwsCredentialsProvider signingCredentials, Region region) {
        // Some operations make the SDK put a prefix in front of the host name (for example "data-" for
        // Cloud Map DiscoverInstances), which would turn 127.0.0.1 into a host that does not exist. The
        // emulator guide's fix for Java: turn host prefix injection off. Merged into whatever override
        // configuration the caller already set.
        ClientOverrideConfiguration callers = builder.overrideConfiguration();
        ClientOverrideConfiguration overrides = (callers == null ? ClientOverrideConfiguration.builder() : callers.toBuilder())
                .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true)
                .build();
        return builder
                .endpointOverride(endpoint)              // 1. send requests to the emulator, not to AWS
                .region(region)                          // 2. the Region (normally REGION)
                .credentialsProvider(signingCredentials) // 3. sign with this emulator run's keys (set explicitly,
                                                         //    so AWS_ACCESS_KEY_ID & co. in your environment are ignored)
                .httpClient(httpClient)                  // 4. trust the emulator's HTTPS certificate
                .overrideConfiguration(overrides)
                .build();
    }

    /**
     * Calls the emulator's health route, {@code GET /__admin/health}: HTTP 200 with the body "ok" when it
     * is ready. Useful to wait for an emulator you have just started.
     */
    public boolean isHealthy() {
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, trustManagers, null);
            try (HttpClient http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).build()) {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(endpoint.resolve("/__admin/health")).timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && "ok".equals(response.body().strip());
            }
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public URI endpoint() {
        return endpoint;
    }

    public int port() {
        return port;
    }

    /**
     * The trust managers that trust exactly this emulator's {@code ca.pem}, for HTTP clients that
     * {@link #client} does not build, such as the Netty client of the async SDK clients (sample 25).
     */
    public TrustManager[] trustManagers() {
        return trustManagers.clone();
    }

    /** The folder holding this run's credentials, certificate and config file. */
    public Path directory() {
        return dir;
    }

    /**
     * The credentials of this emulator run, read from {@code emulator_credentials}; or placeholder keys
     * if the emulator runs with {@code --auth off} and so wrote no credentials file.
     */
    public AwsCredentialsProvider credentials() {
        return credentials;
    }

    /** Closes the shared HTTP client. Close the service clients you built first (try-with-resources does). */
    @Override
    public void close() {
        httpClient.close();
    }

    /** A trust manager that trusts exactly one certificate: the emulator's {@code ca.pem}. */
    private static TrustManager[] trustOnly(Path caFile) {
        try (InputStream pem = Files.newInputStream(caFile)) {
            X509Certificate certificate =
                    (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("aws-emulator", certificate);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return factory.getTrustManagers();
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot read the emulator's certificate " + caFile, e);
        }
    }

    /**
     * The SDK reads the emulator's credentials file like any AWS shared credentials file: its
     * {@code [default]} profile holds the access key and secret key of this run.
     */
    private static AwsCredentialsProvider credentialsFrom(Path credentialsFile) {
        ProfileFile file = ProfileFile.builder().content(credentialsFile).type(ProfileFile.Type.CREDENTIALS).build();
        return ProfileCredentialsProvider.builder().profileFile(file).profileName("default").build();
    }
}
