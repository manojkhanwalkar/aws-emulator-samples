package samples.spring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Sample 24, the application side: how an application might build its AWS SDK beans from properties.
 *
 * <p>In production none of the {@code aws.*} properties below are set, and the client is built the
 * default way (AWS endpoint, default credentials chain, the JVM's trust store). A test sets them to
 * point the very same beans at an emulator. This is the usual Spring way to keep test-only wiring out
 * of the code: the code reads properties; the test supplies them (see {@code S24_SpringBootTest}).
 *
 * <ul>
 *   <li>{@code aws.endpoint}: endpoint override, e.g. {@code https://127.0.0.1:9297}</li>
 *   <li>{@code aws.region}: the Region (default us-east-1)</li>
 *   <li>{@code aws.credentials-file}: a shared credentials file whose [default] profile signs requests</li>
 *   <li>{@code aws.ca-file}: a PEM certificate to trust, instead of the JVM's trust store</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AwsClientsConfig {

    @Bean(destroyMethod = "close")
    SdkHttpClient awsHttpClient(@Value("${aws.ca-file:}") String caFile) {
        ApacheHttpClient.Builder http = ApacheHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(30));
        if (!caFile.isBlank()) {
            TrustManager[] trust = trustOnly(Path.of(caFile));
            http.tlsTrustManagersProvider(() -> trust);
        }
        return http.build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(SdkHttpClient awsHttpClient,
                      @Value("${aws.endpoint:}") String endpoint,
                      @Value("${aws.region:us-east-1}") String region,
                      @Value("${aws.credentials-file:}") String credentialsFile) {
        AwsCredentialsProvider credentials = credentialsFile.isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : ProfileCredentialsProvider.builder().profileName("default").profileFile(ProfileFile.builder()
                        .content(Path.of(credentialsFile)).type(ProfileFile.Type.CREDENTIALS).build()).build();
        var builder = S3Client.builder().region(Region.of(region)).credentialsProvider(credentials).httpClient(awsHttpClient);
        if (!endpoint.isBlank()) {
            // An endpoint override (the emulator): path-style S3 and no host prefixes, as the guide advises.
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true)
                    .overrideConfiguration(o -> o.putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true));
        }
        return builder.build();
    }

    private static TrustManager[] trustOnly(Path pem) {
        try (InputStream in = Files.newInputStream(pem)) {
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("ca", certificate);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return factory.getTrustManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot read the certificate " + pem, e);
        }
    }
}
