package qa.fanar.testsupport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectingSubscriberTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void collectsItemsThenCompletion() throws Exception {
        CollectingSubscriber<Integer> subscriber = CollectingSubscriber.unbounded();
        try (SubmissionPublisher<Integer> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(subscriber);
            assertTrue(subscriber.awaitSubscription(WAIT));
            assertNotNull(subscriber.subscription());
            publisher.submit(1);
            publisher.submit(2);
            publisher.submit(3);
        }

        assertEquals(List.of(1, 2, 3), subscriber.awaitCompletion(WAIT));
        assertTrue(subscriber.isCompleted());
        assertTrue(subscriber.isTerminated());
        assertNull(subscriber.error());
        assertThrows(AssertionError.class, () -> subscriber.awaitError(WAIT), "completed normally is not an error");
    }

    @Test
    void reportsTheError() throws Exception {
        CollectingSubscriber<Integer> subscriber = CollectingSubscriber.unbounded();
        IOException boom = new IOException("boom");
        SubmissionPublisher<Integer> publisher = new SubmissionPublisher<>();
        publisher.subscribe(subscriber);
        publisher.submit(7);
        assertTrue(subscriber.awaitItems(1, WAIT), "closeExceptionally may discard undelivered items");
        publisher.closeExceptionally(boom);

        assertSame(boom, subscriber.awaitError(WAIT));
        assertFalse(subscriber.isCompleted());
        assertTrue(subscriber.isTerminated());
        assertEquals(List.of(7), subscriber.items());
        AssertionError error = assertThrows(AssertionError.class, () -> subscriber.awaitCompletion(WAIT));
        assertSame(boom, error.getCause());
    }

    @Test
    void demandIsDrivenByHandWhenRequestingZero() throws Exception {
        CollectingSubscriber<String> subscriber = CollectingSubscriber.requesting(0);
        try (SubmissionPublisher<String> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(subscriber);
            assertTrue(subscriber.awaitSubscription(WAIT));
            publisher.submit("a");
            publisher.submit("b");

            assertFalse(subscriber.awaitItems(1, Duration.ofMillis(100)), "nothing was requested yet");
            subscriber.request(1);
            assertTrue(subscriber.awaitItems(1, WAIT));
            assertEquals(List.of("a"), subscriber.items());
            subscriber.request(1);
            assertTrue(subscriber.awaitItems(2, WAIT));
        }
        assertEquals(List.of("a", "b"), subscriber.awaitCompletion(WAIT));
    }

    @Test
    void awaitReportsTimeoutsWithWhatWasReceived() throws Exception {
        CollectingSubscriber<Integer> subscriber = CollectingSubscriber.unbounded();
        try (SubmissionPublisher<Integer> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(subscriber);
            publisher.submit(1);
            assertTrue(subscriber.awaitItems(1, WAIT));

            assertFalse(subscriber.awaitTerminal(Duration.ofMillis(50)));
            AssertionError completion = assertThrows(AssertionError.class,
                    () -> subscriber.awaitCompletion(Duration.ofMillis(50)));
            assertTrue(completion.getMessage().contains("1 item(s) received"), completion.getMessage());
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> subscriber.awaitError(Duration.ofMillis(50)));
            assertTrue(failure.getMessage().contains("no terminal signal"), failure.getMessage());
        }
    }

    @Test
    void cancelStopsDelivery() throws Exception {
        CollectingSubscriber<Integer> subscriber = CollectingSubscriber.requesting(1);
        try (SubmissionPublisher<Integer> publisher = new SubmissionPublisher<>()) {
            publisher.subscribe(subscriber);
            publisher.submit(1);
            assertTrue(subscriber.awaitItems(1, WAIT));
            subscriber.cancel();
            publisher.submit(2);
        }
        assertFalse(subscriber.awaitTerminal(Duration.ofMillis(100)), "a cancelled subscription gets no terminal signal");
        assertEquals(List.of(1), subscriber.items());
    }

    @Test
    void guardsMisuse() {
        CollectingSubscriber<Integer> subscriber = CollectingSubscriber.unbounded();
        assertNull(subscriber.subscription());
        assertThrows(IllegalStateException.class, () -> subscriber.request(1));
        assertThrows(IllegalStateException.class, subscriber::cancel);
        assertThrows(IllegalArgumentException.class, () -> CollectingSubscriber.requesting(-1));
        assertThrows(NullPointerException.class, () -> subscriber.onSubscribe(null));
        assertThrows(NullPointerException.class, () -> subscriber.onError(null));
    }
}
