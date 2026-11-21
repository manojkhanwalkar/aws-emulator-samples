package samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

/** The helpers the samples share, without an emulator. */
class ChecksTest {

    @Test
    void expectPassesOrStopsTheSample() {
        Checks.expect(true, "unused");
        assertEquals("it broke", assertThrows(IllegalStateException.class, () -> Checks.expect(false, "it broke")).getMessage());
    }

    @Test
    void outcomeDescribesSuccessAndAwsErrors() {
        assertEquals("accepted, 3 queues", Checks.outcome(() -> "3 queues"));
        AwsServiceException denied = AwsServiceException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build();
        assertEquals("refused, HTTP 403 AccessDenied", Checks.outcome(() -> {
            throw denied;
        }));
    }

    @Test
    void expectFailureReturnsTheExpectedExceptionOnly() {
        IllegalArgumentException wanted = new IllegalArgumentException("wanted");
        assertSame(wanted, Checks.expectFailure(IllegalArgumentException.class, () -> {
            throw wanted;
        }));
        UncheckedIOException other = new UncheckedIOException(new IOException("other"));
        assertSame(other, assertThrows(UncheckedIOException.class, () -> Checks.expectFailure(IllegalArgumentException.class, () -> {
            throw other;
        })));
        assertTrue(assertThrows(IllegalStateException.class, () -> Checks.expectFailure(IllegalArgumentException.class, () -> { }))
                .getMessage().contains("but the call succeeded"));
    }

    @Test
    void rootCauseFindsTheInnermostException() {
        IOException root = new IOException("sync-127.0.0.1");
        assertSame(root, Checks.rootCause(new IllegalStateException(new UncheckedIOException(root))));
        assertNull(Checks.rootCause(new IllegalStateException()).getCause());
    }

    @Test
    void pauseStopsWhenInterrupted() {
        Checks.pause(Duration.ofMillis(1));
        Thread.currentThread().interrupt();
        assertThrows(IllegalStateException.class, () -> Checks.pause(Duration.ofSeconds(5)));
        assertTrue(Thread.interrupted()); // the interrupt flag is kept (and cleared here)
    }
}
