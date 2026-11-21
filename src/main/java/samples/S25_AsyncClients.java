package samples;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import samples.emulator.Emulator;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Sample 25: the asynchronous clients ({@code S3AsyncClient}, {@code DynamoDbAsyncClient}, ...) against
 * the emulator. Their calls return a {@code CompletableFuture} at once, so one thread can have many
 * requests in flight.
 *
 * <p>The connection settings are the same four as for the synchronous clients (endpoint, Region,
 * credentials, HTTPS trust), but the HTTP client is different, and so is where the trust goes:
 * <ul>
 *   <li>Sync clients use an {@code SdkHttpClient} (Apache here), built with
 *       {@code ApacheHttpClient.builder().tlsTrustManagersProvider(...)}.</li>
 *   <li>Async clients use an {@code SdkAsyncHttpClient}: the Netty-based {@code NettyNioAsyncHttpClient}
 *       ({@code netty-nio-client} module), which has its own {@code tlsTrustManagersProvider(...)}.
 *       Give it the same trust managers, built from the emulator's {@code ca.pem}.</li>
 * </ul>
 * {@code Emulator.client(...)} only takes sync builders, so this sample sets the four settings itself,
 * plus no host prefixes, just as {@code Emulator.client} does.
 */
public final class S25_AsyncClients {

    private S25_AsyncClients() {}

    static {
        // Netty 4.1 uses sun.misc.Unsafe, and JDK 24+ prints a warning about it. Netty works without it;
        // this must be set before the first Netty class loads.
        System.setProperty("io.netty.noUnsafe", "true");
    }

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 25. Async clients on the Netty HTTP client ==");
        String suffix = Names.suffix();
        String bucket = "samples-async-" + suffix;
        String table = "samples-async-" + suffix;

        // One Netty client, shared by both service clients (it owns the event loop threads and the
        // connection pool). It trusts exactly the emulator's certificate.
        try (SdkAsyncHttpClient netty = NettyNioAsyncHttpClient.builder()
                     .tlsTrustManagersProvider(emulator::trustManagers)
                     .connectionTimeout(Duration.ofSeconds(5))
                     .readTimeout(Duration.ofSeconds(30))
                     .maxConcurrency(50)
                     .build();
             S3AsyncClient s3 = S3AsyncClient.builder()
                     .endpointOverride(emulator.endpoint())         // 1. the emulator, not AWS
                     .region(Emulator.REGION)                       // 2. one Region
                     .credentialsProvider(emulator.credentials())   // 3. this run's keys
                     .httpClient(netty)                             // 4. the HTTP client that trusts ca.pem
                     .forcePathStyle(true)
                     .overrideConfiguration(o -> o.putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
                     .build();
             DynamoDbAsyncClient dynamo = DynamoDbAsyncClient.builder()
                     .endpointOverride(emulator.endpoint())
                     .region(Emulator.REGION)
                     .credentialsProvider(emulator.credentials())
                     .httpClient(netty)
                     .overrideConfiguration(o -> o.putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
                     .build()) {

            // S3: 20 uploads in flight at once, from this one thread; join() waits for all of them.
            s3.createBucket(b -> b.bucket(bucket)).join();
            long start = System.nanoTime();
            List<CompletableFuture<?>> uploads = IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> s3.putObject(p -> p.bucket(bucket).key("photo-" + i + ".txt"),
                            AsyncRequestBody.fromString("photo " + i)))
                    .<CompletableFuture<?>>map(f -> f)
                    .toList();
            CompletableFuture.allOf(uploads.toArray(CompletableFuture[]::new)).join();
            System.out.println("Uploaded 20 objects concurrently in " + (System.nanoTime() - start) / 1_000_000 + " ms");

            // Futures compose: read one object back, then transform the result, without blocking in between.
            String text = s3.getObject(g -> g.bucket(bucket).key("photo-7.txt"), AsyncResponseTransformer.toBytes())
                    .thenApply(bytes -> bytes.asUtf8String().toUpperCase())
                    .join();
            System.out.println("Read photo-7.txt back, then upper-cased it: " + text);

            // DynamoDB: create a table, wait with the async waiter, then a put followed by a get.
            dynamo.createTable(t -> t.tableName(table).billingMode("PAY_PER_REQUEST")
                    .attributeDefinitions(a -> a.attributeName("id").attributeType(ScalarAttributeType.S))
                    .keySchema(k -> k.attributeName("id").keyType(KeyType.HASH))).join();
            dynamo.waiter().waitUntilTableExists(w -> w.tableName(table)).join();
            String name = dynamo.putItem(p -> p.tableName(table)
                            .item(Map.of("id", AttributeValue.fromS("u-1"), "name", AttributeValue.fromS("Ada"))))
                    .thenCompose(put -> dynamo.getItem(g -> g.tableName(table).key(Map.of("id", AttributeValue.fromS("u-1")))))
                    .thenApply(got -> got.item().get("name").s())
                    .join();
            System.out.println("DynamoDB async put, then get: " + name);

            // Clean up, again concurrently.
            CompletableFuture.allOf(IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> s3.deleteObject(d -> d.bucket(bucket).key("photo-" + i + ".txt")))
                    .toArray(CompletableFuture[]::new)).join();
            s3.deleteBucket(b -> b.bucket(bucket)).join();
            dynamo.deleteTable(d -> d.tableName(table)).join();
            System.out.println("Deleted the objects, the bucket and the table");
        }
        System.out.println();
    }
}
