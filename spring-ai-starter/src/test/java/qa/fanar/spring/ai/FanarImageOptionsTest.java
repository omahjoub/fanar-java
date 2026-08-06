package qa.fanar.spring.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FanarImageOptionsTest {

    @Test
    void builderRoundtripsAllFields() {
        FanarImageOptions o = FanarImageOptions.builder()
                .model("Fanar-Oryx-IG-2")
                .n(1)
                .width(1024)
                .height(768)
                .responseFormat("b64_json")
                .style("photorealistic")
                .revise(false)
                .build();

        assertThat(o.getModel()).isEqualTo("Fanar-Oryx-IG-2");
        assertThat(o.getN()).isEqualTo(1);
        assertThat(o.getWidth()).isEqualTo(1024);
        assertThat(o.getHeight()).isEqualTo(768);
        assertThat(o.getResponseFormat()).isEqualTo("b64_json");
        assertThat(o.getStyle()).isEqualTo("photorealistic");
        assertThat(o.getRevise()).isFalse();
    }

    @Test
    void unsetFieldsStayNull() {
        FanarImageOptions o = FanarImageOptions.builder().build();
        assertThat(o.getModel()).isNull();
        assertThat(o.getN()).isNull();
        assertThat(o.getWidth()).isNull();
        assertThat(o.getHeight()).isNull();
        assertThat(o.getResponseFormat()).isNull();
        assertThat(o.getStyle()).isNull();
        assertThat(o.getRevise()).isNull();
    }
}
