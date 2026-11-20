package samples;

import java.nio.file.Files;
import java.util.Map;
import samples.emulator.Emulator;
import samples.emulator.EmulatorProcess;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Sample 9: start a private emulator from your program, use it, and stop it. No emulator needs to be
 * running for this one; {@code aws-emulator} only needs to be installed.
 *
 * <p>This is the pattern for tests: each run gets its own emulator on a free port ({@code -p 0}),
 * starts with nothing in it, and leaves nothing behind. Several runs can go side by side, each with its
 * own port, keys and resources. See also {@code EmulatorBackedTest} for the same thing in a JUnit test.
 *
 * <p>It shows three things:
 * <ol>
 *   <li>The emulator reports the port it got in its startup log line, and is ready right after.</li>
 *   <li>Two emulators are separate: a table made in one does not exist in the other.</li>
 *   <li>A clean stop deletes the emulator's files (its keys and certificate) and all its resources.</li>
 * </ol>
 */
public final class S09_PrivateEmulatorPerRun {

    public static void main(String[] args) {
        System.out.println("== 9. A private emulator for each run ==");
        String table = "samples-private";

        try (EmulatorProcess first = EmulatorProcess.start();
             Emulator emulator = first.emulator();
             DynamoDbClient dynamo = emulator.client(DynamoDbClient.builder())) {
            System.out.println("Started a private emulator on port " + first.port() + " (picked by the OS)");

            dynamo.createTable(t -> t.tableName(table)
                    .attributeDefinitions(a -> a.attributeName("id").attributeType(ScalarAttributeType.S))
                    .keySchema(k -> k.attributeName("id").keyType(KeyType.HASH))
                    .billingMode("PAY_PER_REQUEST"));
            dynamo.waiter().waitUntilTableExists(w -> w.tableName(table));
            dynamo.putItem(p -> p.tableName(table).item(Map.of("id", AttributeValue.fromS("1"))));
            System.out.println("Its tables: " + dynamo.listTables().tableNames());

            // A second emulator at the same time: its own port, keys and (empty) state.
            try (EmulatorProcess second = EmulatorProcess.start();
                 Emulator other = second.emulator();
                 DynamoDbClient otherDynamo = other.client(DynamoDbClient.builder())) {
                System.out.println("A second one on port " + second.port() + " has tables: "
                        + otherDynamo.listTables().tableNames() + " (separate state)");
            }

            first.close(); // stop it now, to show what a clean stop leaves behind
            boolean keysGone = !Files.exists(emulator.directory().resolve("emulator_credentials"));
            System.out.println("Stopped. Its keys and certificate are deleted: " + keysGone
                    + "; its table went with it (everything was in memory).");
        }
        System.out.println();
    }
}
