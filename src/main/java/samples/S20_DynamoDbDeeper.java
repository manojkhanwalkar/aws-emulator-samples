package samples;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.paginators.QueryIterable;

/**
 * Sample 20: DynamoDB beyond put and get, on one table of bank accounts.
 *
 * <ol>
 *   <li><b>Transactions.</b> {@code TransactWriteItems} moves money between two accounts: both writes
 *       happen, or neither. A transfer larger than the balance fails its condition, and the whole
 *       transaction is cancelled with {@code TransactionCanceledException}. Its
 *       {@code cancellationReasons} say, item by item, which condition failed.</li>
 *   <li><b>A global secondary index (GSI)</b> to query by another attribute (the branch), and
 *       <b>pagination</b>: with {@code Limit}, each page ends with a {@code LastEvaluatedKey} that starts
 *       the next one. The SDK's paginator does that loop for you.</li>
 *   <li><b>Time to live (TTL).</b> Turn TTL on for an attribute that holds an expiry time in epoch
 *       seconds. On AWS, expired items are deleted in the background, typically within a few days, so
 *       tests should not wait for the deletion; they can check the setting, and filter out expired
 *       items when reading, as the sample does.</li>
 * </ol>
 */
public final class S20_DynamoDbDeeper {

    private S20_DynamoDbDeeper() {}

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 20. DynamoDB: transactions, GSI, pagination, TTL ==");
        String table = "samples-accounts-" + Names.suffix();

        try (DynamoDbClient dynamo = emulator.client(DynamoDbClient.builder())) {
            // Key: accountId. GSI "by-branch": partition key branch, sort key accountId.
            dynamo.createTable(t -> t.tableName(table).billingMode("PAY_PER_REQUEST")
                    .attributeDefinitions(
                            a -> a.attributeName("accountId").attributeType(ScalarAttributeType.S),
                            a -> a.attributeName("branch").attributeType(ScalarAttributeType.S))
                    .keySchema(k -> k.attributeName("accountId").keyType(KeyType.HASH))
                    .globalSecondaryIndexes(g -> g.indexName("by-branch")
                            .keySchema(k -> k.attributeName("branch").keyType(KeyType.HASH),
                                    k -> k.attributeName("accountId").keyType(KeyType.RANGE))
                            .projection(p -> p.projectionType(ProjectionType.ALL))));
            dynamo.waiter().waitUntilTableExists(w -> w.tableName(table));
            System.out.println("Created table " + table + " with the index by-branch");
            try {
                // Seven accounts: five in the Leeds branch, two in York.
                for (int i = 1; i <= 7; i++) {
                    String id = "acc-" + i;
                    String branch = i <= 5 ? "leeds" : "york";
                    dynamo.putItem(p -> p.tableName(table).item(Map.of("accountId", s(id), "branch", s(branch),
                            "balance", n(100))));
                }
                System.out.println("Added 7 accounts with a balance of 100 each");

                transactions(dynamo, table);
                indexAndPagination(dynamo, table);
                timeToLive(dynamo, table);
            } finally {
                dynamo.deleteTable(d -> d.tableName(table));
            }
            System.out.println("Deleted the table");
        }
        System.out.println();
    }

    private static void transactions(DynamoDbClient dynamo, String table) {
        // Transfer 60 from acc-1 to acc-2: works, both balances change together.
        transfer(dynamo, table, "acc-1", "acc-2", 60);
        System.out.println("Transfer 60 acc-1 -> acc-2: done. Balances: " + balance(dynamo, table, "acc-1")
                + " and " + balance(dynamo, table, "acc-2"));

        // Transfer 60 again: acc-1 has only 40, its condition fails, and nothing is written.
        TransactionCanceledException e = Checks.expectFailure(TransactionCanceledException.class,
                () -> transfer(dynamo, table, "acc-1", "acc-2", 60));
        System.out.println("Transfer 60 again: " + e.awsErrorDetails().errorCode() + ". Reasons, per item:");
        List<CancellationReason> reasons = e.cancellationReasons();
        for (int i = 0; i < reasons.size(); i++) {
            System.out.println("  item " + i + ": " + reasons.get(i).code()
                    + (reasons.get(i).message() == null ? "" : " - " + reasons.get(i).message()));
        }
        System.out.println("  Balances unchanged: " + balance(dynamo, table, "acc-1") + " and "
                + balance(dynamo, table, "acc-2"));
    }

    /** Debit {@code from} (only if it has enough money) and credit {@code to}, in one transaction. */
    private static void transfer(DynamoDbClient dynamo, String table, String from, String to, int amount) {
        dynamo.transactWriteItems(t -> t.transactItems(
                TransactWriteItem.builder().update(u -> u.tableName(table).key(Map.of("accountId", s(from)))
                        .updateExpression("SET balance = balance - :amount")
                        .conditionExpression("balance >= :amount")
                        .expressionAttributeValues(Map.of(":amount", n(amount)))).build(),
                TransactWriteItem.builder().update(u -> u.tableName(table).key(Map.of("accountId", s(to)))
                        .updateExpression("SET balance = balance + :amount")
                        .expressionAttributeValues(Map.of(":amount", n(amount)))).build()));
    }

    private static void indexAndPagination(DynamoDbClient dynamo, String table) {
        // One page by hand: Limit 2, and LastEvaluatedKey says where the next page starts.
        QueryResponse page = dynamo.query(q -> q.tableName(table).indexName("by-branch")
                .keyConditionExpression("branch = :b").expressionAttributeValues(Map.of(":b", s("leeds"))).limit(2));
        System.out.println("Query by-branch for leeds, Limit 2: " + ids(page.items())
                + ", LastEvaluatedKey " + (page.hasLastEvaluatedKey() ? page.lastEvaluatedKey().keySet() : "none"));

        // The paginator follows LastEvaluatedKey until the last page.
        QueryIterable pages = dynamo.queryPaginator(q -> q.tableName(table).indexName("by-branch")
                .keyConditionExpression("branch = :b").expressionAttributeValues(Map.of(":b", s("leeds"))).limit(2));
        int count = 0;
        StringBuilder all = new StringBuilder();
        for (QueryResponse each : pages) {
            count++;
            all.append(ids(each.items()));
        }
        System.out.println("All pages with the paginator: " + count + " pages: " + all);
    }

    private static void timeToLive(DynamoDbClient dynamo, String table) {
        dynamo.updateTimeToLive(u -> u.tableName(table)
                .timeToLiveSpecification(s -> s.attributeName("expiresAt").enabled(true)));
        System.out.println("TTL: " + dynamo.describeTimeToLive(d -> d.tableName(table)).timeToLiveDescription()
                .timeToLiveStatusAsString() + " on attribute expiresAt");

        // A session that expired an hour ago. It may still be there until TTL deletes it, so filter on read.
        long now = Instant.now().getEpochSecond();
        dynamo.putItem(p -> p.tableName(table).item(Map.of("accountId", s("session-1"), "branch", s("web"),
                "expiresAt", n(now - 3600))));
        int live = dynamo.scan(sc -> sc.tableName(table).filterExpression("attribute_exists(expiresAt) AND expiresAt > :now")
                .expressionAttributeValues(Map.of(":now", n(now)))).count();
        System.out.println("Live sessions (expired ones filtered out on read): " + live);
    }

    private static String balance(DynamoDbClient dynamo, String table, String id) {
        return dynamo.getItem(g -> g.tableName(table).key(Map.of("accountId", s(id))).consistentRead(true))
                .item().get("balance").n();
    }

    private static List<String> ids(List<Map<String, AttributeValue>> items) {
        return items.stream().map(i -> i.get("accountId").s()).toList();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
