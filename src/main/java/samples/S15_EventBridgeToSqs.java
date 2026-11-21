package samples;

import java.util.List;
import java.util.Map;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Sample 15: services working together. An Amazon EventBridge rule matches some of your custom events
 * by an event pattern and delivers them to an SQS queue.
 *
 * <p>The pattern below matches {@code OrderPlaced} events from {@code samples.shop} with a total of
 * 100 or more (a numeric match). The sample puts two events, one big order and one small, and only the
 * big one arrives.
 *
 * <p>As on AWS, EventBridge may only send to the queue if the queue's policy allows
 * {@code events.amazonaws.com} to. Without that policy, {@code PutEvents} still succeeds, but nothing
 * arrives: the delivery fails quietly (on AWS you would see it in the rule's {@code FailedInvocations}
 * metric). The sample shows that first, because it is the most common reason "my rule doesn't fire".
 */
public final class S15_EventBridgeToSqs {

    private S15_EventBridgeToSqs() {}

    private static final String PATTERN = """
            {"source": ["samples.shop"],
             "detail-type": ["OrderPlaced"],
             "detail": {"total": [{"numeric": [">=", 100]}]}}""";

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 15. EventBridge rule to SQS ==");
        String suffix = Names.suffix();
        String busName = "samples-orders-" + suffix;
        String ruleName = "samples-big-orders-" + suffix;
        String queueName = "samples-big-orders-" + suffix;

        try (EventBridgeClient events = emulator.client(EventBridgeClient.builder());
             SqsClient sqs = emulator.client(SqsClient.builder())) {
            // A custom event bus keeps the sample's events apart from the default bus.
            events.createEventBus(b -> b.name(busName));
            String ruleArn = events.putRule(r -> r.name(ruleName).eventBusName(busName).eventPattern(PATTERN)).ruleArn();
            String queueUrl = sqs.createQueue(q -> q.queueName(queueName)).queueUrl();
            String queueArn = sqs.getQueueAttributes(a -> a.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes().get(QueueAttributeName.QUEUE_ARN);
            events.putTargets(t -> t.rule(ruleName).eventBusName(busName).targets(target -> target.id("queue").arn(queueArn)));
            System.out.println("Created bus " + busName + ", rule " + ruleName + " (total >= 100) and its target queue");

            try {
                // TestEventPattern checks a pattern against an event without sending anything.
                boolean matches = events.testEventPattern(t -> t.eventPattern(PATTERN).event(event("o-0", 99, true)))
                        .result();
                System.out.println("TestEventPattern for a total of 99: " + (matches ? "matches" : "no match"));

                // 1. No queue policy yet: the event is accepted but never delivered.
                putOrders(events, busName, "o-1", 250, "o-2", 20);
                System.out.println("Without a queue policy, the queue received: " + bodies(receive(sqs, queueUrl, 2)));

                // 2. Allow this rule (and only this rule) to send to the queue, and try again.
                sqs.setQueueAttributes(a -> a.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, """
                        {"Version": "2012-10-17",
                         "Statement": [{"Effect": "Allow", "Principal": {"Service": "events.amazonaws.com"},
                                        "Action": "sqs:SendMessage", "Resource": "%s",
                                        "Condition": {"ArnEquals": {"aws:SourceArn": "%s"}}}]}"""
                        .formatted(queueArn, ruleArn))));
                putOrders(events, busName, "o-3", 250, "o-4", 20);
                List<Message> received = receive(sqs, queueUrl, 3);
                System.out.println("With the queue policy, the queue received:");
                received.forEach(m -> System.out.println("  " + m.body()));
                Checks.expect(received.size() == 1 && received.getFirst().body().contains("\"o-3\""),
                        "Expected only the big order o-3");
                System.out.println("-> Only the big order (o-3) matched the pattern; o-4 (total 20) did not.");
            } finally {
                events.removeTargets(r -> r.rule(ruleName).eventBusName(busName).ids("queue"));
                events.deleteRule(r -> r.name(ruleName).eventBusName(busName));
                events.deleteEventBus(b -> b.name(busName));
                sqs.deleteQueue(d -> d.queueUrl(queueUrl));
            }
            System.out.println("Deleted the rule, bus and queue");
        }
        System.out.println();
    }

    /** Puts two OrderPlaced events on the bus. */
    private static void putOrders(EventBridgeClient events, String bus, String firstId, int firstTotal,
                                  String secondId, int secondTotal) {
        int failed = events.putEvents(p -> p.entries(entry(bus, firstId, firstTotal), entry(bus, secondId, secondTotal)))
                .failedEntryCount();
        System.out.println("Put orders " + firstId + " (total " + firstTotal + ") and " + secondId + " (total "
                + secondTotal + "), failed entries: " + failed);
    }

    private static PutEventsRequestEntry entry(String bus, String id, int total) {
        return PutEventsRequestEntry.builder().eventBusName(bus).source("samples.shop").detailType("OrderPlaced")
                .detail(event(id, total, false)).build();
    }

    /** The detail of an order event, or (for TestEventPattern) a whole event with the fields AWS requires. */
    private static String event(String id, int total, boolean whole) {
        String detail = "{\"orderId\": \"%s\", \"total\": %d}".formatted(id, total);
        return !whole ? detail : """
                {"id": "1", "account": "000000000000", "source": "samples.shop", "time": "2026-01-01T00:00:00Z",
                 "region": "us-east-1", "resources": [], "detail-type": "OrderPlaced", "detail": %s}""".formatted(detail);
    }

    private static List<Message> receive(SqsClient sqs, String queueUrl, int waitSeconds) {
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)
                .waitTimeSeconds(waitSeconds)).messages();
        messages.forEach(m -> sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(m.receiptHandle())));
        return messages;
    }

    private static List<String> bodies(List<Message> messages) {
        return messages.stream().map(Message::body).toList();
    }
}
