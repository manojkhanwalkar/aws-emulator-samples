package samples;

import java.util.List;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Sample 4: Amazon SQS. Send messages to a queue, receive them, and delete them once handled.
 *
 * <p>This is the usual SQS consumer loop: <b>receive</b> (the message becomes invisible to other
 * consumers for the visibility timeout), <b>process</b>, then <b>delete</b>. A message that is not
 * deleted comes back after the visibility timeout, so a crashed consumer never loses it.
 */
public final class S04_SqsQueues {

    public static void main(String[] args) {
        try (Emulator emulator = Emulator.connect()) {
            run(emulator);
        }
    }

    static void run(Emulator emulator) {
        System.out.println("== 4. Amazon SQS: send, receive, delete ==");
        String queueName = "samples-tasks-" + Names.suffix();

        try (SqsClient sqs = emulator.client(SqsClient.builder())) {
            // The queue URL looks like AWS's (https://sqs.us-east-1.amazonaws.com/000000000000/...), but it
            // is only an identifier here: the client still sends every request to the emulator's endpoint.
            String queueUrl = sqs.createQueue(c -> c.queueName(queueName)).queueUrl();
            System.out.println("Created queue " + queueUrl);

            for (String task : List.of("resize image 1", "resize image 2", "send welcome email")) {
                sqs.sendMessage(m -> m.queueUrl(queueUrl).messageBody(task));
            }
            System.out.println("Sent 3 messages");

            int handled = 0;
            while (handled < 3) {
                // Long polling (waitTimeSeconds) waits for messages instead of answering empty at once.
                List<Message> batch = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                        .maxNumberOfMessages(10).waitTimeSeconds(2)).messages();
                if (batch.isEmpty()) {
                    break;
                }
                for (Message message : batch) {
                    System.out.println("Received: " + message.body());
                    // Done with it: delete, or it would be delivered again after the visibility timeout.
                    sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                    handled++;
                }
            }
            System.out.println("Handled and deleted " + handled + " messages");

            sqs.deleteQueue(d -> d.queueUrl(queueUrl));
            System.out.println("Deleted the queue");
        }
        System.out.println();
    }
}
