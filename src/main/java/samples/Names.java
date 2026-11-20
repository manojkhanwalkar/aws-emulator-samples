package samples;

import java.util.concurrent.ThreadLocalRandom;

/** Unique names for the resources a sample creates, so you can run any sample again and again. */
final class Names {

    private Names() {}

    /** Six lower-case letters and digits, valid in bucket, table, queue and topic names. */
    static String suffix() {
        return Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36, 36L * 36 * 36 * 36 * 36 * 36), 36);
    }
}
