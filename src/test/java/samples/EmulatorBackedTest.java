package samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import samples.emulator.Emulator;
import samples.emulator.EmulatorProcess;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * How to use AWS Emulator in your own tests: the test class starts a private emulator, the tests use
 * real AWS SDK clients against it, and the emulator stops (with all its data) when the class is done.
 *
 * <ul>
 *   <li>No running emulator needed, and none touched: this one gets a free port of its own.</li>
 *   <li>Every run starts empty, so tests never see leftovers from an earlier run.</li>
 *   <li>If {@code aws-emulator} is not installed, the tests are skipped instead of failing.</li>
 *   <li>The emulator also stops by itself after 10 idle minutes ({@code --idle-timeout}), in case the
 *       test JVM is killed before {@link #stopEmulator()} runs.</li>
 * </ul>
 * Run it from IntelliJ with the green arrow next to the class name, or with {@code mvn test}.
 */
class EmulatorBackedTest {

    private static EmulatorProcess process;
    private static Emulator emulator;

    @BeforeAll
    static void startEmulator() {
        assumeTrue(isInstalled(), "aws-emulator is not installed: skipping (see README.md)");
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

    @Test
    void anObjectWrittenToS3CanBeReadBack() {
        try (S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true))) {
            s3.createBucket(b -> b.bucket("test-bucket"));
            s3.putObject(p -> p.bucket("test-bucket").key("note.txt"), RequestBody.fromString("hello"));

            String read = s3.getObject(g -> g.bucket("test-bucket").key("note.txt"), ResponseTransformer.toBytes())
                    .asUtf8String();

            assertEquals("hello", read);
        }
    }

    @Test
    void aConditionalWriteRefusesToOverwriteAnExistingItem() {
        try (DynamoDbClient dynamo = emulator.client(DynamoDbClient.builder())) {
            dynamo.createTable(t -> t.tableName("test-users")
                    .attributeDefinitions(a -> a.attributeName("id").attributeType(ScalarAttributeType.S))
                    .keySchema(k -> k.attributeName("id").keyType(KeyType.HASH))
                    .billingMode("PAY_PER_REQUEST"));
            dynamo.waiter().waitUntilTableExists(w -> w.tableName("test-users"));
            Map<String, AttributeValue> ada = Map.of("id", AttributeValue.fromS("ada"));
            dynamo.putItem(p -> p.tableName("test-users").item(ada).conditionExpression("attribute_not_exists(id)"));

            // The same exception your code would get from AWS: the error-handling path is tested locally.
            assertThrows(ConditionalCheckFailedException.class, () -> dynamo.putItem(
                    p -> p.tableName("test-users").item(ada).conditionExpression("attribute_not_exists(id)")));
        }
    }

    @Test
    void anEmulatorStartedWithAuthOffAcceptsRequestsWithoutItsKeys() {
        // A second, short-lived emulator just for this test, started with --auth off: it writes no
        // credentials file, and Emulator signs with placeholder keys that it accepts.
        try (EmulatorProcess authOff = EmulatorProcess.start(Duration.ofMinutes(2), "--auth", "off");
             Emulator open = authOff.emulator();
             StsClient sts = open.client(StsClient.builder())) {
            assertFalse(Files.exists(open.directory().resolve("emulator_credentials")));
            assertEquals(Emulator.ACCOUNT_ID, sts.getCallerIdentity().account());
        }
    }

    /** True if the {@code aws-emulator} command is on the PATH. */
    private static boolean isInstalled() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, "aws-emulator"))) {
                return true;
            }
        }
        return false;
    }
}
