package samples;

import samples.emulator.Emulator;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Sample 6: the emulator fails the way AWS fails.
 *
 * <p>For the operations it implements, AWS Emulator aims to return the same errors as AWS for
 * invalid calls: the same HTTP status, error code and message. The SDK turns them into the same typed
 * exceptions, so you can test your error handling locally, without provoking failures in a real
 * account.
 *
 * <p>An operation the emulator does not implement yet answers HTTP 501 {@code NotImplemented}
 * instead, so a gap shows up at once rather than as a silent wrong answer. The last case shows one.
 *
 * <p>Each case below makes one call that fails and prints what came back.
 */
public final class S06_SameErrorsAsAws {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 6. The same errors as AWS ==");

        // DynamoDB table names must be at least 3 characters: AWS rejects "ab" with a ValidationException.
        try (DynamoDbClient dynamo = emulator.client(DynamoDbClient.builder())) {
            expectError("DynamoDB CreateTable with the name \"ab\"", () -> dynamo.createTable(t -> t.tableName("ab")
                    .attributeDefinitions(a -> a.attributeName("id").attributeType(ScalarAttributeType.S))
                    .keySchema(k -> k.attributeName("id").keyType(KeyType.HASH))
                    .billingMode("PAY_PER_REQUEST")));
        }

        // S3: reading from a bucket that does not exist throws the SDK's typed NoSuchBucketException.
        try (S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true))) {
            expectError("S3 GetObject from a missing bucket", () -> {
                try {
                    s3.getObject(g -> g.bucket("samples-no-such-bucket-" + Names.suffix()).key("x"),
                            ResponseTransformer.toBytes());
                } catch (NoSuchBucketException e) {
                    System.out.println("   (caught as the typed NoSuchBucketException)");
                    throw e;
                }
            });
        }

        // SQS: looking up a queue that does not exist throws QueueDoesNotExistException.
        try (SqsClient sqs = emulator.client(SqsClient.builder())) {
            expectError("SQS GetQueueUrl for a missing queue", () -> {
                try {
                    sqs.getQueueUrl(q -> q.queueName("samples-no-such-queue-" + Names.suffix()));
                } catch (QueueDoesNotExistException e) {
                    System.out.println("   (caught as the typed QueueDoesNotExistException)");
                    throw e;
                }
            });
        }

        // A gap, not an invalid call: SNS Publish straight to a phone number (SMS) is valid on AWS, but
        // emulator 1.0.531 does not implement it yet, so it answers HTTP 501 NotImplemented. (The number
        // is a fictional 555 one. A later emulator version may implement this, and then it succeeds.)
        try (SnsClient sns = emulator.client(SnsClient.builder())) {
            expectError("SNS Publish to a phone number (not implemented yet)",
                    () -> sns.publish(p -> p.phoneNumber("+15555550100").message("Your code is 123456")));
        }
        System.out.println();
    }

    /** Runs a call that must fail, and prints the AWS error it failed with. */
    private static void expectError(String what, Runnable call) {
        System.out.println(what + ":");
        try {
            call.run();
            System.out.println("   succeeded (not an error in this emulator version)");
        } catch (AwsServiceException e) {
            System.out.println("   HTTP " + e.statusCode() + "  " + e.awsErrorDetails().errorCode()
                    + ": " + e.awsErrorDetails().errorMessage());
        }
    }
}
