package samples;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import samples.emulator.Emulator;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Sample 18: AWS CloudFormation. Deploy a template that creates an S3 bucket, a DynamoDB table and an
 * SQS queue, use the three resources through the stack's outputs, and delete the stack again.
 *
 * <p>This is how you would test infrastructure as code together with the code that uses it: the same
 * template you deploy to AWS creates the resources in the emulator, and the stack's outputs tell your
 * code their names. Support for CloudFormation varies by resource type (see the fidelity scores);
 * these three common types work in emulator 1.0.531.
 *
 * <p>It also shows a classic: a stack whose bucket still holds objects. On AWS, {@code DeleteStack}
 * then fails ({@code DELETE_FAILED}, "The bucket you tried to delete is not empty"); empty the bucket
 * first. The sample prints what the emulator does.
 */
public final class S18_CloudFormationStack {

    private S18_CloudFormationStack() {}

    /** The template: three resources, named after a parameter, and outputs that tell the code their names. */
    private static final String TEMPLATE = """
            AWSTemplateFormatVersion: "2010-09-09"
            Description: Samples - a bucket, a table and a queue
            Parameters:
              Suffix:
                Type: String
            Resources:
              Uploads:
                Type: AWS::S3::Bucket
                Properties:
                  BucketName: !Sub "samples-cfn-uploads-${Suffix}"
              Orders:
                Type: AWS::DynamoDB::Table
                Properties:
                  TableName: !Sub "samples-cfn-orders-${Suffix}"
                  BillingMode: PAY_PER_REQUEST
                  AttributeDefinitions:
                    - {AttributeName: orderId, AttributeType: S}
                  KeySchema:
                    - {AttributeName: orderId, KeyType: HASH}
              Jobs:
                Type: AWS::SQS::Queue
                Properties:
                  QueueName: !Sub "samples-cfn-jobs-${Suffix}"
                  VisibilityTimeout: 60
            Outputs:
              BucketName: {Value: !Ref Uploads}
              TableName:  {Value: !Ref Orders}
              QueueUrl:   {Value: !Ref Jobs}
              QueueArn:   {Value: !GetAtt Jobs.Arn}
            """;

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 18. CloudFormation: deploy, use and delete a stack ==");
        String suffix = Names.suffix();
        String stackName = "samples-stack-" + suffix;

        try (CloudFormationClient cfn = emulator.client(CloudFormationClient.builder());
             S3Client s3 = emulator.client(S3Client.builder().forcePathStyle(true));
             DynamoDbClient dynamo = emulator.client(DynamoDbClient.builder());
             SqsClient sqs = emulator.client(SqsClient.builder())) {
            // 1. Create the stack and wait until CloudFormation has created every resource.
            cfn.createStack(c -> c.stackName(stackName).templateBody(TEMPLATE)
                    .parameters(p -> p.parameterKey("Suffix").parameterValue(suffix)));
            Stack stack = cfn.waiter().waitUntilStackCreateComplete(d -> d.stackName(stackName)).matched().response()
                    .orElseThrow().stacks().getFirst();
            System.out.println("Stack " + stackName + ": " + stack.stackStatus());
            cfn.describeStackResources(r -> r.stackName(stackName)).stackResources().forEach(r -> System.out.println(
                    "  " + r.logicalResourceId() + " (" + r.resourceType() + "): " + r.physicalResourceId()));

            // 2. The outputs name the resources; the code below never hard-codes a name.
            Map<String, String> outputs = stack.outputs().stream()
                    .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
            System.out.println("Outputs: " + outputs.keySet());
            String bucket = outputs.get("BucketName");
            s3.putObject(p -> p.bucket(bucket).key("hello.txt"), RequestBody.fromString("made by a stack"));
            dynamo.putItem(p -> p.tableName(outputs.get("TableName"))
                    .item(Map.of("orderId", AttributeValue.fromS("o-1"), "status", AttributeValue.fromS("NEW"))));
            sqs.sendMessage(m -> m.queueUrl(outputs.get("QueueUrl")).messageBody("job for o-1"));
            System.out.println("Used them: wrote an object (\""
                    + s3.getObject(g -> g.bucket(bucket).key("hello.txt"), ResponseTransformer.toBytes()).asUtf8String()
                    + "\"), an item and a message");
            // Properties from the template reached the resource:
            String visibility = sqs.getQueueAttributes(a -> a.queueUrl(outputs.get("QueueUrl"))
                    .attributeNamesWithStrings("VisibilityTimeout")).attributesAsStrings().get("VisibilityTimeout");
            System.out.println("The queue's VisibilityTimeout, set in the template: " + visibility);

            // 3. Delete the stack while the bucket still holds an object.
            cfn.deleteStack(d -> d.stackName(stackName));
            StackStatus afterFirstDelete = waitWhileInProgress(cfn, stackName);
            if (afterFirstDelete == StackStatus.DELETE_FAILED) {
                System.out.println("DeleteStack with a non-empty bucket: DELETE_FAILED, as on AWS. Emptying the bucket.");
                s3.deleteObject(d -> d.bucket(bucket).key("hello.txt"));
                cfn.deleteStack(d -> d.stackName(stackName));
                afterFirstDelete = waitWhileInProgress(cfn, stackName);
            } else {
                System.out.println("DeleteStack with a non-empty bucket: " + afterFirstDelete
                        + " (on AWS: DELETE_FAILED, the bucket is not empty)");
            }
            System.out.println("Stack after delete: " + afterFirstDelete);
            boolean tableGone = !dynamo.listTables().tableNames().contains(outputs.get("TableName"));
            boolean bucketGone = s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket));
            System.out.println("Deleting the stack deleted its resources: table " + tableGone + ", bucket " + bucketGone);
        }
        System.out.println();
    }

    /**
     * Waits until a deleting stack is no longer DELETE_IN_PROGRESS and returns its status; a stack that
     * no longer exists counts as DELETE_COMPLETE.
     */
    private static StackStatus waitWhileInProgress(CloudFormationClient cfn, String stackName) {
        for (int i = 0; i < 100; i++) {
            StackStatus status;
            try {
                status = cfn.describeStacks(d -> d.stackName(stackName)).stacks().getFirst().stackStatus();
            } catch (CloudFormationException doesNotExist) {
                return StackStatus.DELETE_COMPLETE;
            }
            if (status != StackStatus.DELETE_IN_PROGRESS) {
                return status;
            }
            Checks.pause(Duration.ofMillis(200));
        }
        throw new IllegalStateException("Stack " + stackName + " is still being deleted");
    }
}
