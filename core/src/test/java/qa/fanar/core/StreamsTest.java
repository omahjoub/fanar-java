package qa.fanar.core;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link Streams#toStream(Flow.Publisher)} — the blocking bridge from the SDK's
 * {@code Flow.Publisher} streaming surface to a sync-shaped {@link Stream} (ADR-004, ADR-005).
 */
class StreamsTest {

    @Test
    void rejectsNullPublisher() {
        assertThrows(NullPointerException.class, () -> Streams.toStream(null));
    }

    @Test
    void emitsEveryItemInOrderThenEnds() {
        try (Stream<String> s = Streams.toStream(publisherOf("a", "b", "c"))) {
            assertEquals(List.of("a", "b", "c"), s.toList());
        }
    }

    @Test
    void anEmptyPublisherYieldsAnEmptyStream() {
        try (Stream<String> s = Streams.toStream(publisherOf())) {
            assertEquals(List.of(), s.toList());
        }
    }

    @Test
    void pullsOneItemAtATimeRatherThanBufferingTheWholeResponse() {
        AtomicLong requested = new AtomicLong();
        Flow.Publisher<String> counting = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int emitted;
            @Override public void request(long n) {
                requested.addAndGet(n);
                if (emitted < 3) {
                    subscriber.onNext("item-" + emitted++);
                } else {
                    subscriber.onComplete();
                }
            }
            @Override public void cancel() { }
        });

        try (Stream<String> s = Streams.toStream(counting)) {
            assertEquals(List.of("item-0", "item-1", "item-2"), s.toList());
        }
        // One up-front request plus one per consumed item — never a batch.
        assertEquals(4, requested.get(), "demand is requested one item ahead, not all at once");
    }

    @Test
    void anUncheckedFailureSurfacesUnwrappedFromTheConsumingOperation() {
        Flow.Publisher<String> failing = failingPublisher(new IllegalStateException("upstream boom"));

        try (Stream<String> s = Streams.toStream(failing)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, s::toList);
            assertEquals("upstream boom", e.getMessage());
        }
    }

    @Test
    void aFanarExceptionPassesThroughAsItself() {
        FanarRateLimitException original = new FanarRateLimitException("slow down");
        try (Stream<String> s = Streams.toStream(failingPublisher(original))) {
            assertSame(original, assertThrows(FanarRateLimitException.class, s::toList),
                    "the typed hierarchy is not re-wrapped (ADR-006)");
        }
    }

    @Test
    void aCheckedFailureIsWrappedSoCallersStillOnlyCatchFanarException() {
        // SseStreamPublisher reports a raw IOException on onError; a caller of the bridge must
        // still only have to catch the sealed FanarException hierarchy.
        IOException cause = new IOException("socket died");
        try (Stream<String> s = Streams.toStream(failingPublisher(cause))) {
            FanarTransportException e = assertThrows(FanarTransportException.class, s::toList);
            assertSame(cause, e.getCause());
            assertTrue(e.getMessage().contains("socket died"));
        }
    }

    @Test
    void anErrorIsRethrownRatherThanWrapped() {
        StackOverflowError fatal = new StackOverflowError("nope");
        try (Stream<String> s = Streams.toStream(failingPublisher(fatal))) {
            assertSame(fatal, assertThrows(StackOverflowError.class, s::toList));
        }
    }

    @Test
    void itemsAlreadyDeliveredAreConsumedBeforeTheFailureIsRaised() {
        Flow.Publisher<String> failsAfterTwo = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int emitted;
            @Override public void request(long n) {
                if (emitted < 2) {
                    subscriber.onNext("v" + emitted++);
                } else {
                    subscriber.onError(new IllegalStateException("late boom"));
                }
            }
            @Override public void cancel() { }
        });

        List<String> seen = new ArrayList<>();
        try (Stream<String> s = Streams.toStream(failsAfterTwo)) {
            assertThrows(IllegalStateException.class, () -> s.forEach(seen::add));
        }
        assertEquals(List.of("v0", "v1"), seen, "the failure does not discard what already arrived");
    }

    @Test
    void closingEarlyCancelsTheSubscription() {
        AtomicBoolean cancelled = new AtomicBoolean();
        SubmissionPublisher<String> publisher = new SubmissionPublisher<>();

        Stream<String> s = Streams.toStream(subscriber -> publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription sub) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { sub.request(n); }
                    @Override public void cancel() { cancelled.set(true); sub.cancel(); }
                });
            }
            @Override public void onNext(String item) { subscriber.onNext(item); }
            @Override public void onError(Throwable t) { subscriber.onError(t); }
            @Override public void onComplete() { subscriber.onComplete(); }
        }));

        publisher.submit("first");
        assertEquals("first", s.findFirst().orElseThrow());
        s.close();

        assertTrue(cancelled.get(), "abandoning the stream must release the subscription");
        publisher.close();
    }

    @Test
    void closingIsIdempotent() {
        AtomicLong cancels = new AtomicLong();
        Flow.Publisher<String> p = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { subscriber.onComplete(); }
            @Override public void cancel() { cancels.incrementAndGet(); }
        });

        Stream<String> s = Streams.toStream(p);
        assertEquals(List.of(), s.toList());
        s.close();
        s.close();
        assertEquals(1, cancels.get(), "the subscription is cancelled at most once");
    }

    @Test
    void cancellingBeforeOnSubscribeArrivesStillCancels() {
        // A publisher that defers onSubscribe past the close: the bridge must remember the
        // cancellation and apply it when the subscription finally shows up.
        AtomicBoolean cancelled = new AtomicBoolean();
        List<Flow.Subscriber<? super String>> captured = new ArrayList<>();

        Stream<String> s = Streams.toStream(captured::add);
        s.close();

        captured.getFirst().onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        assertTrue(cancelled.get(), "a late subscription is cancelled immediately");
    }

    @Test
    void anInterruptedConsumerFailsAsATransportError() throws Exception {
        // A publisher that subscribes but never emits: the consumer parks in take().
        Flow.Publisher<String> silent = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { }
            @Override public void cancel() { }
        });

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean wrapped = new AtomicBoolean();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();

        Thread consumer = new Thread(() -> {
            try (Stream<String> s = Streams.toStream(silent)) {
                started.countDown();
                s.toList();
            } catch (FanarTransportException e) {
                wrapped.set(true);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        consumer.start();

        assertTrue(started.await(5, TimeUnit.SECONDS));
        // The consumer is either parked in take() or about to be; interrupting is safe either way
        // because take() re-checks the flag on entry.
        consumer.interrupt();
        consumer.join(5_000);

        assertFalse(consumer.isAlive(), "the interrupt must break the park");
        assertTrue(wrapped.get(), "an interrupt surfaces as FanarTransportException");
        assertTrue(interruptFlagRestored.get(), "the interrupt flag is restored before throwing");
    }

    @Test
    void traversingPastTheEndReturnsRatherThanBlocking() {
        // The Stream contract forbids advancing after the end, but a stray call must not park the
        // caller forever: the terminal marker is re-armed, so the answer stays "no more".
        Spliterator<String> sp = Streams.toStream(publisherOf("only")).spliterator();
        assertTrue(sp.tryAdvance(v -> assertEquals("only", v)));
        assertFalse(sp.tryAdvance(v -> fail("no item expected")));
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertFalse(sp.tryAdvance(v -> fail("no item expected"))),
                "a second traversal past the end must return, not block");
    }

    @Test
    void traversingPastAFailureReturnsRatherThanBlocking() {
        Spliterator<String> sp = Streams.toStream(
                failingPublisher(new IllegalStateException("boom"))).spliterator();
        assertThrows(IllegalStateException.class, () -> sp.tryAdvance(v -> fail("no item expected")));
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertFalse(sp.tryAdvance(v -> fail("no item expected"))),
                "a failed stream is finished, not retried and not blocked");
    }

    @Test
    void theSpliteratorRejectsANullAction() {
        Spliterator<String> sp = Streams.toStream(publisherOf("x")).spliterator();
        assertThrows(NullPointerException.class, () -> sp.tryAdvance(null));
    }

    // --- fixtures ------------------------------------------------------------------------

    /** Emits the given items on demand, one per {@code request}, then completes. */
    private static Flow.Publisher<String> publisherOf(String... items) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int index;
            @Override public void request(long n) {
                if (index < items.length) {
                    subscriber.onNext(items[index++]);
                } else {
                    subscriber.onComplete();
                }
            }
            @Override public void cancel() { }
        });
    }

    /** Reports the given throwable on the first demand. */
    private static Flow.Publisher<String> failingPublisher(Throwable t) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { subscriber.onError(t); }
            @Override public void cancel() { }
        });
    }
}
