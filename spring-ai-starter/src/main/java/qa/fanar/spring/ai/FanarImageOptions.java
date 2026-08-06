package qa.fanar.spring.ai;

import org.springframework.ai.image.ImageOptions;

/**
 * Fanar-specific {@link ImageOptions}: the standard portable knobs plus Fanar's {@code revise}
 * flag (automatic prompt revision for style, quality, and cultural alignment — server default
 * {@code true}).
 *
 * <p>Pass an instance on the {@code ImagePrompt}; {@code FanarImageModel} maps
 * {@link #getModel()} like any {@link ImageOptions} and additionally applies {@code revise}.
 * The remaining portable getters (n, width, height, response format, style) have no Fanar wire
 * field and are dropped, as documented on the adapter. Any other implementation keeps working —
 * {@code revise} is then simply unset (ADR-024).</p>
 *
 * @author Oussama Mahjoub
 */
public final class FanarImageOptions implements ImageOptions {

    private final String model;
    private final Integer n;
    private final Integer width;
    private final Integer height;
    private final String responseFormat;
    private final String style;
    private final Boolean revise;

    private FanarImageOptions(Builder b) {
        this.model = b.model;
        this.n = b.n;
        this.width = b.width;
        this.height = b.height;
        this.responseFormat = b.responseFormat;
        this.style = b.style;
        this.revise = b.revise;
    }

    /** Start a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override public String getModel() { return model; }
    @Override public Integer getN() { return n; }
    @Override public Integer getWidth() { return width; }
    @Override public Integer getHeight() { return height; }
    @Override public String getResponseFormat() { return responseFormat; }
    @Override public String getStyle() { return style; }

    /**
     * Whether Fanar may auto-revise the prompt (server default {@code true}); {@code false}
     * keeps the prompt verbatim, {@code null} accepts the default.
     */
    public Boolean getRevise() { return revise; }

    /** Fluent builder; every field defaults to {@code null} ("use the adapter/server default"). */
    public static final class Builder {

        private String model;
        private Integer n;
        private Integer width;
        private Integer height;
        private String responseFormat;
        private String style;
        private Boolean revise;

        private Builder() {
            // use FanarImageOptions.builder()
        }

        public Builder model(String model) { this.model = model; return this; }
        public Builder n(Integer n) { this.n = n; return this; }
        public Builder width(Integer width) { this.width = width; return this; }
        public Builder height(Integer height) { this.height = height; return this; }
        public Builder responseFormat(String responseFormat) { this.responseFormat = responseFormat; return this; }
        public Builder style(String style) { this.style = style; return this; }
        public Builder revise(Boolean revise) { this.revise = revise; return this; }

        public FanarImageOptions build() {
            return new FanarImageOptions(this);
        }
    }
}
