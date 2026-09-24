package qa.fanar.core.sadiq;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import qa.fanar.core.chat.ChoiceError;
import qa.fanar.core.chat.ChoiceToken;
import qa.fanar.core.chat.DoneChunk;
import qa.fanar.core.chat.ErrorChunk;
import qa.fanar.core.chat.ProgressChunk;
import qa.fanar.core.chat.ProgressMessage;
import qa.fanar.core.chat.StreamEvent;
import qa.fanar.core.chat.TokenChunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeepResearchEventTest {

    private static final DeepResearchReport REPORT =
            new DeepResearchReport(null, null, "Title", null, null, null, null, null, null);

    @Test
    void sealedHierarchyIsExhaustive() {
        List<DeepResearchEvent> run = List.of(
                new ProgressChunk("c", 1L, null, new ProgressMessage("planning", "تخطيط")),
                new TokenChunk("c", 2L, "Fanar-Sadiq-2", List.of(new ChoiceToken(0, null, "draft"))),
                new ReportChunk("c", 3L, "Fanar-Sadiq-2", REPORT),
                new DoneChunk("c", 4L, "Fanar-Sadiq-2", List.of(), null, Map.of("depth", "quick")),
                new ErrorChunk("c", 5L, "Fanar-Sadiq-2", List.of(new ChoiceError(0, null, "boom"))));

        List<String> kinds = run.stream().map(event -> switch (event) {
            case ProgressChunk p -> "progress:" + p.message().en();
            case TokenChunk    t -> "token:" + t.choices().getFirst().content();
            case ReportChunk   r -> "report:" + r.report().title();
            case DoneChunk     d -> "done:" + d.metadata().get("depth");
            case ErrorChunk    e -> "error:" + e.choices().getFirst().content();
        }).toList();

        assertEquals(List.of("progress:planning", "token:draft", "report:Title", "done:quick", "error:boom"), kinds);
    }

    @Test
    void sharedRecordsBelongToBothUnions() {
        // The four chat records are the deep-research events too — one record, two sealed unions.
        TokenChunk token = new TokenChunk("c", 1L, "Fanar", List.of());
        assertInstanceOf(StreamEvent.class, token);
        assertInstanceOf(DeepResearchEvent.class, token);
        assertInstanceOf(DeepResearchEvent.class, new DoneChunk("c", 1L, "Fanar", List.of(), null, null));
        assertInstanceOf(DeepResearchEvent.class, new ErrorChunk("c", 1L, "Fanar", List.of()));
        assertInstanceOf(DeepResearchEvent.class, new ProgressChunk("c", 1L, "Fanar", new ProgressMessage("a", "b")));
    }

    @Test
    void commonMetadataFieldsAccessibleViaSealedInterface() {
        DeepResearchEvent event = new ReportChunk("c_id", 42L, null, REPORT);
        assertEquals("c_id", event.id());
        assertEquals(42L, event.created());
        assertNull(event.model(), "the server may omit the model on some events");
    }
}
