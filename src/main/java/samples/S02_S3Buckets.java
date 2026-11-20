package samples;

import java.util.List;
import samples.emulator.Emulator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Sample 2: Amazon S3. Create a bucket, store objects, read and list them, then clean up.
 *
 * <p>The emulator <b>keeps</b> what you create, like AWS does, until it stops: everything is in memory,
 * and restarting the emulator gives you an empty, clean state.
 *
 * <p>One S3-specific point: the emulator only accepts the bucket name in the URL <b>path</b>
 * ({@code https://127.0.0.1:9297/my-bucket/key}), not in the host name ({@code my-bucket.127.0.0.1}).
 * Because the endpoint is an IP address, the SDK already uses the path. {@code forcePathStyle(true)}
 * makes that explicit, and keeps S3 working if you ever write {@code localhost} instead of
 * {@code 127.0.0.1} (the Java SDK would then put the bucket in the host name).
 */
public final class S02_S3Buckets {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 2. Amazon S3: buckets and objects ==");
        // Names are unique per run, so running a sample twice never clashes with what is still there.
        String bucket = "samples-bucket-" + Names.suffix();

        try (S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true))) {
            s3.createBucket(b -> b.bucket(bucket));
            System.out.println("Created bucket " + bucket);

            s3.putObject(p -> p.bucket(bucket).key("greetings/hello.txt").contentType("text/plain"),
                    RequestBody.fromString("Hello from AWS Emulator!"));
            s3.putObject(p -> p.bucket(bucket).key("greetings/bye.txt"), RequestBody.fromString("Bye!"));
            System.out.println("Stored 2 objects");

            String hello = s3.getObject(g -> g.bucket(bucket).key("greetings/hello.txt"), ResponseTransformer.toBytes())
                    .asUtf8String();
            System.out.println("Read greetings/hello.txt: \"" + hello + "\"");

            List<String> keys = s3.listObjectsV2(l -> l.bucket(bucket).prefix("greetings/")).contents().stream()
                    .map(S3Object::key).toList();
            System.out.println("Objects under greetings/: " + keys);

            // Clean up: a bucket must be empty before it can be deleted (the emulator enforces this too).
            keys.forEach(key -> s3.deleteObject(d -> d.bucket(bucket).key(key)));
            s3.deleteBucket(d -> d.bucket(bucket));
            System.out.println("Deleted the objects and the bucket");
        }
        System.out.println();
    }
}
