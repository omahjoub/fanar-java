package qa.fanar.spring.ai;

import org.junit.jupiter.api.Test;

import qa.fanar.core.audio.QuranReciter;

import static org.assertj.core.api.Assertions.assertThat;

class FanarTextToSpeechOptionsTest {

    @Test
    void builderRoundtripsAllFields() {
        FanarTextToSpeechOptions o = FanarTextToSpeechOptions.builder()
                .model("Fanar-Sadiq-TTS-1")
                .voice("Radwa")
                .format("wav")
                .speed(1.25)
                .withEmotion(true)
                .quranReciter(QuranReciter.MAHER_AL_MUAIQLY)
                .build();

        assertThat(o.getModel()).isEqualTo("Fanar-Sadiq-TTS-1");
        assertThat(o.getVoice()).isEqualTo("Radwa");
        assertThat(o.getFormat()).isEqualTo("wav");
        assertThat(o.getSpeed()).isEqualTo(1.25);
        assertThat(o.getWithEmotion()).isTrue();
        assertThat(o.getQuranReciter()).isEqualTo(QuranReciter.MAHER_AL_MUAIQLY);
    }

    @Test
    void unsetFieldsStayNull() {
        FanarTextToSpeechOptions o = FanarTextToSpeechOptions.builder().build();
        assertThat(o.getModel()).isNull();
        assertThat(o.getVoice()).isNull();
        assertThat(o.getFormat()).isNull();
        assertThat(o.getSpeed()).isNull();
        assertThat(o.getWithEmotion()).isNull();
        assertThat(o.getQuranReciter()).isNull();
    }

}
