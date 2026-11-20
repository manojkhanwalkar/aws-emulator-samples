package samples;

import java.util.Map;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Sample 3: Amazon DynamoDB. Create a table, write and read items, query, and see a conditional
 * write refused, as on AWS.
 *
 * <p>The table has a composite key: {@code customer} (partition key) and {@code orderId} (sort key), so
 * a query can fetch one customer's orders. A conditional put ({@code attribute_not_exists}) refuses to
 * overwrite an existing order with a {@code ConditionalCheckFailedException}, the same exception
 * AWS throws, so your error handling can be tested locally.
 */
public final class S03_DynamoDbTables {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 3. Amazon DynamoDB: tables and items ==");
        String table = "samples-orders-" + Names.suffix();

        try (DynamoDbClient dynamo = emulator.client(DynamoDbClient.builder())) {
            dynamo.createTable(t -> t.tableName(table)
                    .attributeDefinitions(
                            a -> a.attributeName("customer").attributeType(ScalarAttributeType.S),
                            a -> a.attributeName("orderId").attributeType(ScalarAttributeType.S))
                    .keySchema(
                            k -> k.attributeName("customer").keyType(KeyType.HASH),
                            k -> k.attributeName("orderId").keyType(KeyType.RANGE))
                    .billingMode(BillingMode.PAY_PER_REQUEST));
            // Tables start as CREATING, as on AWS; the SDK's waiter polls until it is ACTIVE.
            dynamo.waiter().waitUntilTableExists(w -> w.tableName(table));
            System.out.println("Created table " + table);

            putOrder(dynamo, table, "ada", "o-1", "Notebook", 12);
            putOrder(dynamo, table, "ada", "o-2", "Pencils", 3);
            putOrder(dynamo, table, "bob", "o-3", "Desk lamp", 40);
            System.out.println("Wrote 3 orders");

            Map<String, AttributeValue> order = dynamo.getItem(g -> g.tableName(table)
                    .key(Map.of("customer", s("ada"), "orderId", s("o-2")))).item();
            System.out.println("GetItem ada/o-2: " + order.get("product").s() + " for $" + order.get("price").n());

            QueryResponse adas = dynamo.query(q -> q.tableName(table)
                    .keyConditionExpression("customer = :c")
                    .expressionAttributeValues(Map.of(":c", s("ada"))));
            System.out.println("Query customer = ada: " + adas.count() + " orders "
                    + adas.items().stream().map(i -> i.get("orderId").s()).toList());

            // A conditional write: create o-1 only if it does not exist yet. It does, so this is refused.
            try {
                dynamo.putItem(p -> p.tableName(table)
                        .item(Map.of("customer", s("ada"), "orderId", s("o-1"), "product", s("Overwritten!")))
                        .conditionExpression("attribute_not_exists(orderId)"));
            } catch (ConditionalCheckFailedException expected) {
                System.out.println("Conditional put on an existing order refused: ConditionalCheckFailedException");
            }

            dynamo.deleteTable(d -> d.tableName(table));
            System.out.println("Deleted the table");
        }
        System.out.println();
    }

    private static void putOrder(DynamoDbClient dynamo, String table, String customer, String orderId, String product, int price) {
        dynamo.putItem(p -> p.tableName(table).item(Map.of(
                "customer", s(customer),
                "orderId", s(orderId),
                "product", s(product),
                "price", AttributeValue.fromN(Integer.toString(price)))));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
