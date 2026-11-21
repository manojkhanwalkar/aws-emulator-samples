package samples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Sample 19: two SQS features that are hard to test without a real queue.
 *
 * <ol>
 *   <li><b>Dead-letter queue.</b> A redrive policy with {@code maxReceiveCount: 2}: a message received
 *       twice without being deleted (a consumer that keeps failing) is moved to the dead-letter queue
 *       instead of being delivered a third time. {@code StartMessageMoveTask} then moves it back once
 *       the bug is fixed.</li>
 *   <li><b>FIFO queue.</b> Messages in one message group arrive in the order they were sent, and a
 *       message sent again with the same deduplication ID within 5 minutes is accepted but not
 *       delivered twice. While a message of a group is being processed, the next one of that group
 *       waits: the order holds even with several consumers.</li>
 * </ol>
 */
public final class S19_SqsDeadLetterAndFifo {

    private S19_SqsDeadLetterAndFifo() {}

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 19. SQS: dead-letter queue and FIFO ==");
        String suffix = Names.suffix();
        try (SqsClient sqs = emulator.client(SqsClient.builder())) {
            deadLetterQueue(sqs, suffix);
            fifo(sqs, suffix);
        }
        System.out.println();
    }

    private static void deadLetterQueue(SqsClient sqs, String suffix) {
        String dlqUrl = sqs.createQueue(q -> q.queueName("samples-payments-dlq-" + suffix)).queueUrl();
        String dlqArn = arnOf(sqs, dlqUrl);
        // The redrive policy is an attribute of the source queue. A 1-second visibility timeout makes a
        // message that was received but not deleted visible again quickly, for the demo.
        String queueUrl = sqs.createQueue(q -> q.queueName("samples-payments-" + suffix).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\": \"" + dlqArn + "\", \"maxReceiveCount\": \"2\"}"))).queueUrl();
        try {
            sqs.sendMessage(m -> m.queueUrl(queueUrl).messageBody("charge order o-7"));
            System.out.println("Sent \"charge order o-7\" to a queue with maxReceiveCount 2");

            // A consumer that fails: it receives the message but never deletes it.
            for (int attempt = 1; attempt <= 3; attempt++) {
                List<Message> got = sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(3)
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
                if (got.isEmpty()) {
                    System.out.println("  Receive " + attempt + ": nothing, the message has left the queue");
                } else {
                    System.out.println("  Receive " + attempt + ": \"" + got.getFirst().body() + "\", receive count "
                            + got.getFirst().attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                            + " (processing fails, not deleted)");
                }
            }
            List<Message> dead = sqs.receiveMessage(r -> r.queueUrl(dlqUrl).waitTimeSeconds(3)).messages();
            System.out.println("Dead-letter queue holds: " + dead.stream().map(Message::body).toList());
            Checks.expect(!dead.isEmpty(), "The message should be in the dead-letter queue");
            // Make it visible in the DLQ again at once, so the move task can take it.
            sqs.changeMessageVisibility(c -> c.queueUrl(dlqUrl).receiptHandle(dead.getFirst().receiptHandle())
                    .visibilityTimeout(0));

            // Bug fixed: move the dead letters back to their source queue (a "redrive").
            sqs.startMessageMoveTask(t -> t.sourceArn(dlqArn));
            List<Message> back = sqs.receiveMessage(r -> r.queueUrl(queueUrl).waitTimeSeconds(5)).messages();
            System.out.println("After StartMessageMoveTask, the source queue has: " + back.stream().map(Message::body).toList());
            back.forEach(m -> sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(m.receiptHandle())));
        } finally {
            sqs.deleteQueue(d -> d.queueUrl(queueUrl));
            sqs.deleteQueue(d -> d.queueUrl(dlqUrl));
        }
    }

    private static void fifo(SqsClient sqs, String suffix) {
        // FIFO queue names must end in .fifo.
        String queueUrl = sqs.createQueue(q -> q.queueName("samples-ledger-" + suffix + ".fifo")
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
        try {
            // Three steps for account A, in order; step 2 is sent twice with the same deduplication ID
            // (a producer retrying after a timeout). One step for account B, in its own group.
            send(sqs, queueUrl, "account-A", "A1", "A: open account");
            send(sqs, queueUrl, "account-A", "A2", "A: deposit 100");
            send(sqs, queueUrl, "account-A", "A2", "A: deposit 100");   // duplicate, dropped
            send(sqs, queueUrl, "account-B", "B1", "B: open account");
            send(sqs, queueUrl, "account-A", "A3", "A: withdraw 30");
            System.out.println("Sent 5 messages to a FIFO queue (one a duplicate) in groups account-A and account-B");

            // While "A: open account" is in flight (received, not deleted), no other account-A message is
            // handed out: only account B's message comes with it.
            List<Message> first = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(2))
                    .messages();
            List<Message> meanwhile = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)
                    .waitTimeSeconds(1)).messages();
            System.out.println("First receive: " + bodies(first) + "; a second consumer meanwhile gets: "
                    + bodies(meanwhile) + " (group account-A is blocked until the first is deleted)");
            deleteAll(sqs, queueUrl, first);
            deleteAll(sqs, queueUrl, meanwhile);

            // Drain the rest in order.
            List<String> rest = new ArrayList<>();
            while (true) {
                List<Message> batch = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)
                        .waitTimeSeconds(1)).messages();
                if (batch.isEmpty()) {
                    break;
                }
                rest.addAll(bodies(batch));
                deleteAll(sqs, queueUrl, batch);
            }
            System.out.println("Then, in order: " + rest + "  (the duplicate deposit was delivered once)");
            Checks.expect(rest.equals(List.of("A: deposit 100", "A: withdraw 30")), "Unexpected FIFO delivery: " + rest);
        } finally {
            sqs.deleteQueue(d -> d.queueUrl(queueUrl));
        }
        System.out.println("Deleted the queues");
    }

    private static void send(SqsClient sqs, String queueUrl, String group, String dedupId, String body) {
        sqs.sendMessage(m -> m.queueUrl(queueUrl).messageGroupId(group).messageDeduplicationId(dedupId).messageBody(body));
    }

    private static void deleteAll(SqsClient sqs, String queueUrl, List<Message> messages) {
        messages.forEach(m -> sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(m.receiptHandle())));
    }

    private static List<String> bodies(List<Message> messages) {
        return messages.stream().map(Message::body).toList();
    }

    private static String arnOf(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(a -> a.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }
}
