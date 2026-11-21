package samples;

import java.util.List;
import samples.emulator.Emulator;
import samples.emulator.EmulatorProcess;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Sample 11: Regions. One emulator keeps most resources apart by Region, as AWS does, but not all
 * (CloudWatch Logs log groups are shared by every Region), so to test several Regions reliably, run one
 * emulator per Region. Starts its own emulators: {@code aws-emulator}
 * only needs to be installed.
 *
 * <p>The emulator accepts any Region in a request, but the guide warns: "it doesn't keep resources
 * separate by Region or account. Some services share resources across all Regions, and others keep
 * them per Region." Part 1 shows both kinds in emulator 1.0.531:
 * <ul>
 *   <li>a DynamoDB table made in us-east-1 is not visible from eu-west-1 (as on AWS), but</li>
 *   <li>a CloudWatch Logs log group made in us-east-1 <i>is</i> listed from eu-west-1 (on AWS it would
 *       not be).</li>
 * </ul>
 * So code that relies on Regions being separate can pass or fail for the wrong reason. Part 2 runs one
 * private emulator per Region; then nothing is shared, whatever the service.
 */
public final class S11_OneEmulatorPerRegion {

    private S11_OneEmulatorPerRegion() {}

    private static final Region HOME = Region.US_EAST_1;
    private static final Region OTHER = Region.EU_WEST_1;

    public static void main(String[] args) {
        run();
    }

    static void run() {
        System.out.println("== 11. Regions: one emulator per Region ==");
        String name = "samples-regional-" + Names.suffix();

        // Part 1: one emulator, two Regions.
        try (EmulatorProcess process = EmulatorProcess.start();
             Emulator emulator = process.emulator();
             DynamoDbClient dynamoHome = emulator.client(DynamoDbClient.builder(), HOME);
             DynamoDbClient dynamoOther = emulator.client(DynamoDbClient.builder(), OTHER);
             CloudWatchLogsClient logsHome = emulator.client(CloudWatchLogsClient.builder(), HOME);
             CloudWatchLogsClient logsOther = emulator.client(CloudWatchLogsClient.builder(), OTHER)) {
            System.out.println("One emulator (port " + process.port() + "), clients for " + HOME + " and " + OTHER + ":");

            createTable(dynamoHome, name);
            logsHome.createLogGroup(g -> g.logGroupName(name));
            System.out.println("  Created DynamoDB table and log group " + name + " in " + HOME);

            boolean tableSeen = dynamoOther.listTables().tableNames().contains(name);
            boolean logGroupSeen = logGroupNames(logsOther).contains(name);
            System.out.println("  Seen from " + OTHER + ": table " + yesNo(tableSeen) + ", log group " + yesNo(logGroupSeen)
                    + "   (on AWS: neither)");
            if (logGroupSeen) {
                System.out.println("  -> CloudWatch Logs shares its log groups across Regions in this emulator.");
            }
        } // the private emulator stops here, and everything in it is gone

        // Part 2: one emulator per Region, the way the guide recommends for multi-Region tests. Each
        // client uses the emulator of its own Region.
        try (EmulatorProcess eastProcess = EmulatorProcess.start();
             EmulatorProcess westProcess = EmulatorProcess.start();
             Emulator east = eastProcess.emulator();
             Emulator west = westProcess.emulator();
             DynamoDbClient dynamoEast = east.client(DynamoDbClient.builder(), HOME);
             CloudWatchLogsClient logsEast = east.client(CloudWatchLogsClient.builder(), HOME);
             DynamoDbClient dynamoWest = west.client(DynamoDbClient.builder(), OTHER);
             CloudWatchLogsClient logsWest = west.client(CloudWatchLogsClient.builder(), OTHER)) {
            System.out.println("Two emulators: " + HOME + " on port " + eastProcess.port() + ", "
                    + OTHER + " on port " + westProcess.port() + ":");

            createTable(dynamoEast, name);
            logsEast.createLogGroup(g -> g.logGroupName(name));
            System.out.println("  Created the table and log group in the " + HOME + " emulator");

            boolean tableSeen = dynamoWest.listTables().tableNames().contains(name);
            boolean logGroupSeen = logGroupNames(logsWest).contains(name);
            System.out.println("  Seen from the " + OTHER + " emulator: table " + yesNo(tableSeen)
                    + ", log group " + yesNo(logGroupSeen));
            Checks.expect(!tableSeen && !logGroupSeen, "Two emulators should never share resources");
            System.out.println("-> Separate emulators share nothing: run one per Region to test multi-Region code.");
        }
        System.out.println();
    }

    private static void createTable(DynamoDbClient dynamo, String name) {
        dynamo.createTable(t -> t.tableName(name)
                .attributeDefinitions(a -> a.attributeName("id").attributeType(ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("id").keyType(KeyType.HASH))
                .billingMode("PAY_PER_REQUEST"));
    }

    private static List<String> logGroupNames(CloudWatchLogsClient logs) {
        return logs.describeLogGroups().logGroups().stream().map(LogGroup::logGroupName).toList();
    }

    private static String yesNo(boolean seen) {
        return seen ? "SEEN" : "not seen";
    }
}
