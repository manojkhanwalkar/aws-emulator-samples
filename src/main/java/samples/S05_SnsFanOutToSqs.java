package samples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Sample 5: services working together. An SNS topic fans one message out to two SQS queues.
 *
 * <p>This is where an emulator beats mocks: the message really travels from SNS to SQS inside the
 * emulator, so you test the whole path (topic, subscriptions, queue policy, delivery) on your machine.
 *
 * <p>Steps, the same as on AWS:
 * <ol>
 *   <li>Create a topic and two queues.</li>
 *   <li>Give each queue a policy that lets the topic send to it.</li>
 *   <li>Subscribe each queue to the topic. With <b>raw message delivery</b> the queue receives the
 *       message text as published, instead of SNS's JSON envelope around it.</li>
 *   <li>Publish once; read the message from both queues.</li>
 * </ol>
 */
public final class S05_SnsFanOutToSqs {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 5. SNS to SQS fan-out (services working together) ==");
        String suffix = Names.suffix();

        try (SnsClient sns = emulator.client(SnsClient.builder());
             SqsClient sqs = emulator.client(SqsClient.builder())) {

            String topicArn = sns.createTopic(t -> t.name("samples-orders-" + suffix)).topicArn();
            System.out.println("Created topic " + topicArn);

            List<String> queueUrls = new ArrayList<>();
            for (String consumer : List.of("billing", "shipping")) {
                String queueUrl = sqs.createQueue(c -> c.queueName("samples-" + consumer + "-" + suffix)).queueUrl();
                String queueArn = sqs.getQueueAttributes(a -> a.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);

                // Allow this topic (and only it) to send to the queue. AWS requires this. The emulator
                // does not evaluate policies the way AWS does, so a wrong policy would still work here:
                // set it anyway, so the same code is right on AWS.
                String policy = """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Principal":{"Service":"sns.amazonaws.com"},"Action":"sqs:SendMessage",
                          "Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}"""
                        .formatted(queueArn, topicArn);
                sqs.setQueueAttributes(a -> a.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, policy)));

                sns.subscribe(s -> s.topicArn(topicArn).protocol("sqs").endpoint(queueArn)
                        .attributes(Map.of("RawMessageDelivery", "true")));
                queueUrls.add(queueUrl);
                System.out.println("Subscribed queue " + consumer + " to the topic");
            }

            sns.publish(p -> p.topicArn(topicArn).message("Order o-42 was placed"));
            System.out.println("Published one message to the topic");

            for (String queueUrl : queueUrls) {
                String body = sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(5)).messages().stream()
                        .findFirst().map(m -> m.body()).orElse("(nothing arrived)");
                System.out.println("Queue " + queueUrl.substring(queueUrl.lastIndexOf('/') + 1) + " received: " + body);
            }

            queueUrls.forEach(url -> sqs.deleteQueue(d -> d.queueUrl(url)));
            sns.deleteTopic(d -> d.topicArn(topicArn));
            System.out.println("Deleted the queues and the topic");
        }
        System.out.println();
    }
}
