package samples;

import java.time.Duration;
import java.util.List;
import samples.emulator.Emulator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.FilterRuleName;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Sample 14: services working together. Upload a file to S3, and S3 tells an SQS queue and runs a
 * Lambda function, inside the emulator, as it would on AWS.
 *
 * <p>What it shows:
 * <ol>
 *   <li><b>S3 to SQS.</b> The queue needs a policy that lets S3 send to it, as on AWS. Without it the
 *       emulator refuses the notification configuration ({@code InvalidArgument: Unable to validate the
 *       following destination configurations}), the same error AWS gives. When the configuration is
 *       saved, S3 first sends an {@code s3:TestEvent}; then one message per new object, with the AWS
 *       event format ({@code Records[0].s3.object.key}). A prefix filter limits it to {@code uploads/}.</li>
 *   <li><b>S3 to Lambda.</b> {@code AddPermission} lets S3 invoke the function. The function prints the
 *       key of new objects under {@code invoices/}, and its output lands in CloudWatch Logs ({@code /aws/lambda/<name>}) because its role
 *       allows it to write logs, so we read the log to see that it ran.</li>
 * </ol>
 */
public final class S14_S3EventNotifications {

    private S14_S3EventNotifications() {}

    /** The function: logs the key of each new object. Python, run by the emulator itself (see sample 16). */
    private static final String FUNCTION_SOURCE = """
            def handler(event, context):
                for record in event["Records"]:
                    print("New object:", record["s3"]["object"]["key"])
                return {"handled": len(event["Records"])}
            """;

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 14. S3 event notifications to SQS and Lambda ==");
        String suffix = Names.suffix();
        String bucket = "samples-uploads-" + suffix;
        String queueName = "samples-new-files-" + suffix;
        String functionName = "samples-on-upload-" + suffix;
        String roleName = "samples-on-upload-role-" + suffix;

        try (S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true));
             SqsClient sqs = emulator.client(SqsClient.builder());
             LambdaClient lambda = emulator.client(LambdaClient.builder());
             IamClient iam = emulator.client(IamClient.builder());
             CloudWatchLogsClient logs = emulator.client(CloudWatchLogsClient.builder())) {
            s3.createBucket(b -> b.bucket(bucket));
            String queueUrl = sqs.createQueue(q -> q.queueName(queueName)).queueUrl();
            String queueArn = sqs.getQueueAttributes(a -> a.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes().get(QueueAttributeName.QUEUE_ARN);
            String roleArn = LambdaFunctions.createRole(iam, roleName, "lambda.amazonaws.com", LambdaFunctions.LOGS_POLICY);
            String functionArn = LambdaFunctions.createFunction(lambda, functionName, "python3.12",
                    "lambda_function.handler", roleArn, "lambda_function.py", FUNCTION_SOURCE);
            System.out.println("Created bucket " + bucket + ", queue " + queueName + " and function " + functionName);
            try {
                // 1. Without a queue policy, S3 may not send to the queue: the configuration is refused.
                S3Exception refused = Checks.expectFailure(S3Exception.class,
                        () -> putNotifications(s3, bucket, queueArn, functionArn));
                System.out.println("Notification config without a queue policy: refused, "
                        + refused.awsErrorDetails().errorCode() + ": " + refused.awsErrorDetails().errorMessage());

                // 2. Let S3 (for this bucket only) send to the queue, and invoke the function.
                sqs.setQueueAttributes(a -> a.queueUrl(queueUrl).attributes(java.util.Map.of(QueueAttributeName.POLICY, """
                        {"Version": "2012-10-17",
                         "Statement": [{"Effect": "Allow", "Principal": {"Service": "s3.amazonaws.com"},
                                        "Action": "sqs:SendMessage", "Resource": "%s",
                                        "Condition": {"ArnLike": {"aws:SourceArn": "arn:aws:s3:::%s"}}}]}"""
                        .formatted(queueArn, bucket))));
                lambda.addPermission(p -> p.functionName(functionName).statementId("s3-invoke")
                        .action("lambda:InvokeFunction").principal("s3.amazonaws.com").sourceArn("arn:aws:s3:::" + bucket));
                putNotifications(s3, bucket, queueArn, functionArn);
                System.out.println("With a queue policy and a Lambda permission, the configuration is saved");

                // S3 checks a new queue destination by sending it a test event first.
                for (Message message : receive(sqs, queueUrl)) {
                    System.out.println("Queue got: " + describe(message.body()));
                }

                // 3. Upload three files: one for the queue, one for the function, one that matches neither.
                s3.putObject(p -> p.bucket(bucket).key("uploads/report.csv"), RequestBody.fromString("month,total"));
                s3.putObject(p -> p.bucket(bucket).key("invoices/invoice-17.pdf"), RequestBody.fromString("%PDF-fake"));
                s3.putObject(p -> p.bucket(bucket).key("tmp/scratch.txt"), RequestBody.fromString("ignored"));
                System.out.println("Uploaded uploads/report.csv, invoices/invoice-17.pdf and tmp/scratch.txt");

                List<Message> events = receive(sqs, queueUrl);
                for (Message message : events) {
                    System.out.println("Queue got: " + describe(message.body()));
                }
                Checks.expect(events.size() == 1 && events.getFirst().body().contains("\"key\":\"uploads/report.csv\""),
                        "Expected exactly one event, for uploads/report.csv");

                // 4. The function ran too: read what it printed, from its CloudWatch Logs log group.
                List<String> printed = waitForLog(logs, "/aws/lambda/" + functionName, "New object");
                printed.forEach(line -> System.out.println("Function log: " + line.strip()));
                Checks.expect(!printed.isEmpty(), "The function did not log the new object");
            } finally {
                s3.listObjectsV2(l -> l.bucket(bucket)).contents()
                        .forEach(o -> s3.deleteObject(d -> d.bucket(bucket).key(o.key())));
                s3.deleteBucket(b -> b.bucket(bucket));
                sqs.deleteQueue(d -> d.queueUrl(queueUrl));
                lambda.deleteFunction(d -> d.functionName(functionName));
                LambdaFunctions.deleteRole(iam, roleName);
                deleteLogGroup(logs, "/aws/lambda/" + functionName);
            }
            System.out.println("Deleted the bucket, queue, function, role and log group");
        }
        System.out.println();
    }

    /**
     * New objects under uploads/ go to the queue, new objects under invoices/ to the function. (Two rules
     * for the same event with overlapping prefixes are refused as "ambiguously defined", as on AWS.)
     */
    private static void putNotifications(S3Client s3, String bucket, String queueArn, String functionArn) {
        s3.putBucketNotificationConfiguration(n -> n.bucket(bucket).notificationConfiguration(c -> c
                .queueConfigurations(q -> q.queueArn(queueArn).events(Event.S3_OBJECT_CREATED)
                        .filter(f -> f.key(k -> k.filterRules(r -> r.name(FilterRuleName.PREFIX).value("uploads/")))))
                .lambdaFunctionConfigurations(l -> l.lambdaFunctionArn(functionArn).events(Event.S3_OBJECT_CREATED)
                        .filter(f -> f.key(k -> k.filterRules(r -> r.name(FilterRuleName.PREFIX).value("invoices/")))))));
    }

    /** Receives and deletes what the queue has, waiting up to 3 seconds for the first message. */
    private static List<Message> receive(SqsClient sqs, String queueUrl) {
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(3))
                .messages();
        messages.forEach(m -> sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(m.receiptHandle())));
        return messages;
    }

    /**
     * Polls a log group for lines containing {@code text}, for up to 10 seconds. (The function runs
     * asynchronously, so its log group may not even exist yet.)
     */
    private static List<String> waitForLog(CloudWatchLogsClient logs, String group, String text) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<String> lines = List.of();
        while (lines.isEmpty() && System.nanoTime() < deadline) {
            Checks.pause(Duration.ofMillis(250));
            try {
                lines = logs.filterLogEvents(f -> f.logGroupName(group)).events().stream()
                        .map(FilteredLogEvent::message).filter(m -> m.contains(text)).toList();
            } catch (ResourceNotFoundException notYet) {
                // no log group yet: the function has not run
            }
        }
        return lines;
    }

    private static void deleteLogGroup(CloudWatchLogsClient logs, String group) {
        try {
            logs.deleteLogGroup(d -> d.logGroupName(group));
        } catch (ResourceNotFoundException alreadyGone) {
            // the function never ran
        }
    }

    /** The interesting parts of an S3 event message: its event name and object key. */
    private static String describe(String body) {
        java.util.regex.Matcher event = java.util.regex.Pattern.compile("\"(?:Event|eventName)\":\"([^\"]+)\"").matcher(body);
        java.util.regex.Matcher key = java.util.regex.Pattern.compile("\"key\":\"([^\"]+)\"").matcher(body);
        return (event.find() ? event.group(1) : "?") + (key.find() ? ", key " + key.group(1) : "")
                + "   (" + body.length() + " characters of JSON)";
    }
}
