package qa.fanar.core.chat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogprobsTest {

    // --- TopLogprob

    @Test
    void topLogprobHoldsAllFields() {
        TopLogprob tl = new TopLogprob("the", List.of(116, 104, 101), -0.5);
        assertEquals("the", tl.token());
        assertEquals(List.of(116, 104, 101), tl.bytes());
        assertEquals(-0.5, tl.logprob());
    }

    @Test
    void topLogprobRejectsNullToken() {
        assertThrows(NullPointerException.class, () -> new TopLogprob(null, List.of(), -0.1));
    }

    @Test
    void topLogprobNormalizesNullBytesToEmpty() {
        TopLogprob tl = new TopLogprob("x", null, -0.1);
        assertTrue(tl.bytes().isEmpty());
    }

    @Test
    void topLogprobBytesDefensivelyCopiedAndUnmodifiable() {
        List<Integer> src = new ArrayList<>(List.of(1, 2));
        TopLogprob tl = new TopLogprob("t", src, -0.1);
        src.add(3);
        assertEquals(2, tl.bytes().size());
        assertThrows(UnsupportedOperationException.class, () -> tl.bytes().add(3));
    }

    // --- TokenLogprob

    @Test
    void tokenLogprobHoldsAllFields() {
        TopLogprob alt = new TopLogprob("a", List.of(), -0.9);
        TokenLogprob tl = new TokenLogprob("the", List.of(116, 104, 101), -0.3, List.of(alt));
        assertEquals("the", tl.token());
        assertEquals(-0.3, tl.logprob());
        assertEquals(1, tl.topLogprobs().size());
    }

    @Test
    void tokenLogprobRejectsNullToken() {
        assertThrows(NullPointerException.class, () ->
                new TokenLogprob(null, List.of(), -0.1, List.of()));
    }

    @Test
    void tokenLogprobNullCollectionsBecomeEmpty() {
        TokenLogprob tl = new TokenLogprob("x", null, -0.1, null);
        assertTrue(tl.bytes().isEmpty());
        assertTrue(tl.topLogprobs().isEmpty());
    }

    @Test
    void tokenLogprobCollectionsUnmodifiable() {
        TokenLogprob tl = new TokenLogprob("x", List.of(1), -0.1, List.of());
        assertThrows(UnsupportedOperationException.class, () -> tl.bytes().add(2));
        assertThrows(UnsupportedOperationException.class, () ->
                tl.topLogprobs().add(new TopLogprob("y", List.of(), -0.2)));
    }

    // --- ChoiceLogprobs

    @Test
    void choiceLogprobsNullInputsBecomeEmpty() {
        ChoiceLogprobs cl = new ChoiceLogprobs(null, null);
        assertTrue(cl.content().isEmpty());
        assertTrue(cl.refusal().isEmpty());
    }

    @Test
    void choiceLogprobsHoldsPopulatedLists() {
        TokenLogprob tl = new TokenLogprob("x", List.of(), -0.1, List.of());
        ChoiceLogprobs cl = new ChoiceLogprobs(List.of(tl), List.of(tl));
        assertEquals(1, cl.content().size());
        assertEquals(1, cl.refusal().size());
    }

    @Test
    void choiceLogprobsListsUnmodifiable() {
        ChoiceLogprobs cl = new ChoiceLogprobs(null, null);
        TokenLogprob tl = new TokenLogprob("x", List.of(), -0.1, List.of());
        assertThrows(UnsupportedOperationException.class, () -> cl.content().add(tl));
        assertThrows(UnsupportedOperationException.class, () -> cl.refusal().add(tl));
    }
}
