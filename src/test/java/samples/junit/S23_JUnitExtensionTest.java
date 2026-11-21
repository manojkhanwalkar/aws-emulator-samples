package samples.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import samples.emulator.Emulator;
import samples.emulator.EmulatorProcess;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Sample 23: tests written with {@link WithAwsEmulator}. No setup or teardown code: the class gets its
 * own emulator, and each test asks for the clients it needs as parameters.
 */
@WithAwsEmulator
class S23_JUnitExtensionTest {

    /** Lifecycle methods can take clients too: one table for every test of the class. */
    @BeforeAll
    static void createTable(DynamoDbClient dynamo) {
        dynamo.createTable(t -> t.tableName("customers")
                .attributeDefinitions(a -> a.attributeName("id").attributeType(ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("id").keyType(KeyType.HASH))
                .billingMode("PAY_PER_REQUEST"));
        dynamo.waiter().waitUntilTableExists(w -> w.tableName("customers"));
    }

    @Test
    void clientsAreInjectedAndTalkToTheEmulator(StsClient sts) {
        assertEquals(Emulator.ACCOUNT_ID, sts.getCallerIdentity().account());
    }

    @Test
    void s3ClientsComeWithPathStyleAddressing(S3Client s3) {
        s3.createBucket(b -> b.bucket("invoices"));
        s3.putObject(p -> p.bucket("invoices").key("2026/inv-1.txt"), RequestBody.fromString("total 42"));

        String read = s3.getObject(g -> g.bucket("invoices").key("2026/inv-1.txt"), ResponseTransformer.toBytes())
                .asUtf8String();

        assertEquals("total 42", read);
    }

    @Test
    void severalClientsInOneTest(DynamoDbClient dynamo, SqsClient sqs) {
        dynamo.putItem(p -> p.tableName("customers").item(Map.of("id", AttributeValue.fromS("c-1"))));
        String queueUrl = sqs.createQueue(q -> q.queueName("welcome-emails")).queueUrl();
        sqs.sendMessage(m -> m.queueUrl(queueUrl).messageBody("c-1"));

        assertTrue(dynamo.getItem(g -> g.tableName("customers").key(Map.of("id", AttributeValue.fromS("c-1"))))
                .hasItem());
        assertEquals(List.of("c-1"), sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(1)).messages()
                .stream().map(m -> m.body()).toList());
    }

    @Test
    void theEmulatorAndItsProcessCanBeInjectedToo(Emulator emulator, EmulatorProcess process) {
        assertEquals(process.port(), emulator.port());
        assertTrue(emulator.isHealthy());
    }

    @Nested
    class NestedTests {

        @Test
        void shareTheOuterClassesEmulator(DynamoDbClient dynamo) {
            assertTrue(dynamo.listTables().tableNames().contains("customers"));
        }
    }

    @Test
    void parametersTheExtensionDoesNotKnowAreLeftToOthers(org.junit.jupiter.api.TestInfo info) {
        assertFalse(info.getDisplayName().isEmpty());
    }
}

/** A separate class with options of its own: its own emulator, started with {@code --auth off}. */
@WithAwsEmulator(options = {"--auth", "off"})
class S23_JUnitExtensionAuthOffTest {

    @Test
    void optionsReachTheEmulator(Emulator emulator, StsClient sts) {
        assertFalse(Files.exists(emulator.directory().resolve("emulator_credentials")));
        assertEquals(Emulator.ACCOUNT_ID, sts.getCallerIdentity().account());
    }
}
