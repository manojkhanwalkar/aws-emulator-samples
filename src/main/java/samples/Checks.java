package samples;

import java.time.Duration;
import java.util.function.Supplier;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

/** Small helpers the samples share: self-checks, one call's outcome, and polling pauses. */
final class Checks {

    private Checks() {}

    /**
     * Stops the sample if what it just showed did not happen. The samples check their own results, so a
     * change in the emulator (or the SDK) shows up as a failure, not as misleading output.
     */
    static void expect(boolean condition, String whatWentWrong) {
        if (!condition) {
            throw new IllegalStateException(whatWentWrong);
        }
    }

    /**
     * Makes one AWS call and describes its outcome: {@code "accepted, <result>"}, or
     * {@code "refused, HTTP <status> <error code>"} if the service answered with an error. For calls whose
     * outcome is the point of the demo, whichever it is.
     */
    static String outcome(Supplier<String> call) {
        try {
            return "accepted, " + call.get();
        } catch (AwsServiceException e) {
            return "refused, HTTP " + e.statusCode() + " " + e.awsErrorDetails().errorCode();
        }
    }

    /**
     * Makes a call that must fail with {@code type}, and returns the exception so the sample can show it.
     * The sample stops if the call succeeds, or fails some other way.
     */
    static <T extends Throwable> T expectFailure(Class<T> type, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            if (type.isInstance(e)) {
                return type.cast(e);
            }
            throw e;
        }
        throw new IllegalStateException("Expected " + type.getSimpleName() + ", but the call succeeded");
    }

    /** The innermost cause of an exception: often the one that names the real problem. */
    static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Waits between two polls. An interrupt stops the sample. */
    static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
