package samples.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import samples.emulator.Emulator;
import samples.emulator.EmulatorBackedTestSupport;
import samples.emulator.EmulatorProcess;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Sample 24: a Spring Boot test whose application beans talk to a private AWS Emulator.
 *
 * <p>The application ({@link AwsClientsConfig}, {@link ReportArchive}) builds its {@code S3Client} from
 * {@code aws.*} properties. The test starts an emulator before the Spring context, then
 * {@link DynamicPropertySource} supplies the properties whose values are only known at run time: the
 * emulator's endpoint (its port is random), its credentials file and its certificate. The beans need
 * no test-specific code.
 *
 * <p>Order of events: JUnit runs {@code @BeforeAll} (start the emulator), then Spring creates the
 * context, reading the {@code @DynamicPropertySource} values, then the tests run, and {@code @AfterAll}
 * stops the emulator. Spring caches the context until the JVM ends, so its beans outlive the emulator;
 * that is harmless as long as no other test class reuses this context (none does here).
 *
 * <p>Spring is a test-scope dependency of this project only ({@code spring-boot-test}); the samples in
 * {@code src/main} stay plain Java.
 */
@SpringBootTest(classes = S24_SpringBootTest.App.class)
class S24_SpringBootTest {

    /** The application under test: its configuration and its service. */
    @SpringBootConfiguration
    @Import({AwsClientsConfig.class, ReportArchive.class})
    static class App {
    }

    private static EmulatorProcess process;
    private static Emulator emulator;

    @BeforeAll
    static void startEmulator() {
        assumeTrue(EmulatorBackedTestSupport.isInstalled(), "aws-emulator is not installed: skipping (see README.md)");
        process = EmulatorProcess.start();
        emulator = process.emulator();
    }

    @AfterAll
    static void stopEmulator() {
        if (emulator != null) {
            emulator.close();
        }
        if (process != null) {
            process.close();
        }
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        // Suppliers: evaluated when Spring reads the property, after @BeforeAll has started the emulator.
        registry.add("aws.endpoint", () -> emulator.endpoint().toString());
        registry.add("aws.region", () -> "us-east-1");
        registry.add("aws.credentials-file", () -> emulator.directory().resolve("emulator_credentials").toString());
        registry.add("aws.ca-file", () -> emulator.directory().resolve("ca.pem").toString());
        registry.add("reports.bucket", () -> "samples-spring-reports");
    }

    @Autowired
    ReportArchive archive;

    @Autowired
    S3Client s3;

    @Test
    void theApplicationsBeansUseTheEmulator() {
        s3.createBucket(b -> b.bucket("samples-spring-reports"));

        archive.store("q3.txt", "Q3: all good");

        assertEquals("Q3: all good", archive.read("q3.txt"));
        try (StsClient sts = emulator.client(StsClient.builder())) {
            // The same emulator, seen without Spring: the account is the emulator's.
            assertEquals("000000000000", sts.getCallerIdentity().account());
            assertEquals(1, s3.listObjectsV2(l -> l.bucket("samples-spring-reports")).keyCount());
        }
    }
}
