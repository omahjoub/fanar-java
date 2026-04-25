package qa.fanar.core.internal.audio;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import qa.fanar.core.FanarAuthenticationException;
import qa.fanar.core.FanarTransportException;
import qa.fanar.core.RetryPolicy;
import qa.fanar.core.audio.CreateVoiceRequest;
import qa.fanar.core.audio.SpeechToTextResponse;
import qa.fanar.core.audio.SttFormat;
import qa.fanar.core.audio.SttModel;
import qa.fanar.core.audio.TextToSpeechRequest;
import qa.fanar.core.audio.TranscriptionRequest;
import qa.fanar.core.audio.TtsModel;
import qa.fanar.core.audio.TtsResponseFormat;
import qa.fanar.core.audio.Voice;
import qa.fanar.core.audio.VoiceResponse;
import qa.fanar.core.internal.transport.HttpTransport;
import qa.fanar.core.spi.FanarJsonCodec;
import qa.fanar.core.spi.Interceptor;
import qa.fanar.core.spi.ObservabilityPlugin;
import qa.fanar.core.spi.ObservationHandle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioClientImplTest {

    private static final URI BASE = URI.create("https://api.example.com");

    // --- listVoices ------------------------------------------------------------------------

    @Test
    void listVoicesHappyPathDecodesResponse() {
        VoiceResponse canned = new VoiceResponse(List.of("alice"));
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = build(transport, cannedListCodec(canned), List.of());
        assertSame(canned, client.listVoices());
    }

    @Test
    void listVoicesGetsTheVoicesEndpoint() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedListCodec(empty()), List.of()).listVoices();

        HttpRequest sent = captured.get();
        assertEquals("GET", sent.method());
        assertEquals("/v1/audio/voices", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Accept"));
    }

    @Test
    void listVoicesInjectsBearerToken() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedListCodec(empty()), List.of(), "tok").listVoices();
        assertEquals(Optional.of("Bearer tok"), captured.get().headers().firstValue("Authorization"));
    }

    @Test
    void listVoicesAppliesDefaultHeadersAndUserAgent() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        AudioClientImpl client = new AudioClientImpl(
                BASE, cannedListCodec(empty()), () -> "t", List.of(), transport,
                ObservabilityPlugin.noop(), RetryPolicy.disabled(),
                Map.of("X-Test", "true"), "Fanar-Java/0.1");
        client.listVoices();

        assertEquals(Optional.of("true"), captured.get().headers().firstValue("X-Test"));
        assertEquals(Optional.of("Fanar-Java/0.1"), captured.get().headers().firstValue("User-Agent"));
    }

    @Test
    void listVoicesOmitsUserAgentWhenNull() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedListCodec(empty()), List.of()).listVoices();
        assertTrue(captured.get().headers().firstValue("User-Agent").isEmpty());
    }

    @Test
    void listVoicesInvokesUserInterceptorsInOrder() {
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger first = new AtomicInteger(-1);
        AtomicInteger second = new AtomicInteger(-1);
        Interceptor a = (req, ch) -> { first.set(counter.incrementAndGet()); return ch.proceed(req); };
        Interceptor b = (req, ch) -> { second.set(counter.incrementAndGet()); return ch.proceed(req); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        build(transport, cannedListCodec(empty()), List.of(a, b)).listVoices();
        assertEquals(1, first.get());
        assertEquals(2, second.get());
    }

    @Test
    void listVoicesMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, cannedListCodec(empty()), List.of());
        assertThrows(FanarAuthenticationException.class, client::listVoices);
    }

    @Test
    void listVoicesWrapsCodecDecodeFailureAsTransportException() {
        FanarJsonCodec failingDecode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) { /* unused */ }
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = build(transport, failingDecode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, client::listVoices);
        assertTrue(ex.getMessage().contains("Failed to decode VoiceResponse"));
    }

    @Test
    void listVoicesAsyncCompletesSuccessfully() throws Exception {
        VoiceResponse canned = new VoiceResponse(List.of("alice"));
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = build(transport, cannedListCodec(canned), List.of());
        CompletableFuture<VoiceResponse> f = client.listVoicesAsync();
        assertSame(canned, f.get());
    }

    @Test
    void listVoicesAsyncCompletesExceptionally() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, cannedListCodec(empty()), List.of());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> client.listVoicesAsync().get());
        assertInstanceOf(FanarAuthenticationException.class, ex.getCause());
    }

    @Test
    void listVoicesObservationOpensAttributesAndPropagatesHeaders() {
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicInteger attrs = new AtomicInteger();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { attrs.incrementAndGet(); return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of("traceparent", "00-aud"); }
                public void close() { }
            };
        };
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        AudioClientImpl client = new AudioClientImpl(
                BASE, cannedListCodec(empty()), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.listVoices();

        assertEquals("fanar.audio.voices.list", opened.get());
        assertTrue(attrs.get() >= 3);
        assertEquals(Optional.of("00-aud"), captured.get().headers().firstValue("traceparent"));
    }

    @Test
    void listVoicesReportsErrorOnObservationWhenInterceptorThrows() {
        AtomicInteger errored = new AtomicInteger();
        ObservabilityPlugin plugin = name -> errorObs(errored);
        Interceptor blowingUp = (req, ch) -> { throw new RuntimeException("boom"); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, cannedListCodec(empty()), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, client::listVoices);
        assertEquals(1, errored.get());
    }

    // --- createVoice -----------------------------------------------------------------------

    @Test
    void createVoicePostsMultipartBodyToVoicesEndpoint() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "", Map.of()); };
        build(transport, throwOnAnyCodec(), List.of())
                .createVoice(new CreateVoiceRequest("alice", new byte[]{1, 2, 3}, "hello"));

        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/audio/voices", sent.uri().getPath());
        String contentType = sent.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType);

        String body = bodyOf(sent);
        assertTrue(body.contains("name=\"name\"\r\n\r\nalice\r\n"), body);
        assertTrue(body.contains("name=\"audio\"; filename=\"alice.wav\"\r\nContent-Type: audio/wav"), body);
        assertTrue(body.contains("name=\"transcript\"\r\n\r\nhello\r\n"), body);
    }

    @Test
    void createVoiceMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        assertThrows(FanarAuthenticationException.class,
                () -> client.createVoice(new CreateVoiceRequest("alice", new byte[0], "t")));
    }

    @Test
    void createVoiceDrainsBodyOnSuccess() {
        AtomicInteger reads = new AtomicInteger();
        HttpTransport transport = req -> new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() {
                return new InputStream() {
                    public int read() { reads.incrementAndGet(); return -1; }
                };
            }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        client.createVoice(new CreateVoiceRequest("alice", new byte[0], "t"));
        assertEquals(1, reads.get(), "body must have been drained");
    }

    @Test
    void createVoiceWrapsBodyDrainFailureAsTransportException() {
        HttpTransport transport = req -> new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() {
                return new InputStream() {
                    public int read() throws IOException { throw new IOException("body read failed"); }
                };
            }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.createVoice(new CreateVoiceRequest("alice", new byte[0], "t")));
        assertTrue(ex.getMessage().contains("Failed to read response body"), ex.getMessage());
    }

    @Test
    void createVoiceAsyncCompletes() throws Exception {
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        client.createVoiceAsync(new CreateVoiceRequest("alice", new byte[0], "t")).get();
    }

    @Test
    void createVoiceObservationOpens() {
        AtomicReference<String> opened = new AtomicReference<>();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { }
            };
        };
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, throwOnAnyCodec(), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.createVoice(new CreateVoiceRequest("alice", new byte[0], "t"));
        assertEquals("fanar.audio.voices.create", opened.get());
    }

    @Test
    void createVoiceReportsErrorOnObservationWhenInterceptorThrows() {
        AtomicInteger errored = new AtomicInteger();
        ObservabilityPlugin plugin = name -> errorObs(errored);
        Interceptor blowingUp = (req, ch) -> { throw new RuntimeException("boom"); };
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, throwOnAnyCodec(), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class,
                () -> client.createVoice(new CreateVoiceRequest("alice", new byte[0], "t")));
        assertEquals(1, errored.get());
    }

    // --- deleteVoice -----------------------------------------------------------------------

    @Test
    void deleteVoiceUsesPathParamOnVoicesEndpoint() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "", Map.of()); };
        build(transport, throwOnAnyCodec(), List.of()).deleteVoice("alice");

        HttpRequest sent = captured.get();
        assertEquals("DELETE", sent.method());
        assertEquals("/v1/audio/voices/alice", sent.uri().getPath());
    }

    @Test
    void deleteVoiceUrlEncodesNameWithSpecialCharacters() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "", Map.of()); };
        build(transport, throwOnAnyCodec(), List.of()).deleteVoice("a/b c");
        // URLEncoder uses + for space; segments still resolve correctly server-side.
        String raw = captured.get().uri().getRawPath();
        assertTrue(raw.contains("a%2Fb+c"), "path must url-encode special chars: " + raw);
    }

    @Test
    void deleteVoiceMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.deleteVoice("alice"));
    }

    @Test
    void deleteVoiceAsyncCompletes() throws Exception {
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        client.deleteVoiceAsync("alice").get();
    }

    @Test
    void deleteVoiceAsyncCompletesExceptionally() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, throwOnAnyCodec(), List.of());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> client.deleteVoiceAsync("alice").get());
        assertInstanceOf(FanarAuthenticationException.class, ex.getCause());
    }

    @Test
    void deleteVoiceObservationOpens() {
        AtomicReference<String> opened = new AtomicReference<>();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { }
            };
        };
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, throwOnAnyCodec(), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.deleteVoice("alice");
        assertEquals("fanar.audio.voices.delete", opened.get());
    }

    @Test
    void deleteVoiceReportsErrorOnObservationWhenInterceptorThrows() {
        AtomicInteger errored = new AtomicInteger();
        ObservabilityPlugin plugin = name -> errorObs(errored);
        Interceptor blowingUp = (req, ch) -> { throw new RuntimeException("boom"); };
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, throwOnAnyCodec(), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, () -> client.deleteVoice("alice"));
        assertEquals(1, errored.get());
    }

    // --- speech (TTS) ----------------------------------------------------------------------

    @Test
    void speechPostsToSpeechEndpointWithJsonBodyAndAudioAccept() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        byte[] audioBytes = new byte[]{(byte) 0xff, (byte) 0xfb, 0x10, 0x00};
        HttpTransport transport = req -> { captured.set(req); return binaryResponse(200, audioBytes); };
        FanarJsonCodec markerCodec = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) {
                throw new AssertionError("decode should not be called for binary response");
            }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("{\"marker\":true}".getBytes(StandardCharsets.UTF_8));
            }
        };
        byte[] result = build(transport, markerCodec, List.of()).speech(
                TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hello", Voice.HARRY));

        assertArrayEquals(audioBytes, result);
        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/audio/speech", sent.uri().getPath());
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Content-Type"));
        assertEquals(Optional.of("audio/*"), sent.headers().firstValue("Accept"));
        assertEquals("{\"marker\":true}", bodyOf(sent));
    }

    @Test
    void speechMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, encodingCodec(), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.speech(
                TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY)));
    }

    @Test
    void speechWrapsCodecEncodeFailureAsTransportException() {
        FanarJsonCodec failingEncode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) { return null; }
            public void encode(OutputStream s, Object v) throws IOException { throw new IOException("encode"); }
        };
        HttpTransport transport = req -> { throw new AssertionError("should not reach transport"); };
        AudioClientImpl client = build(transport, failingEncode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.speech(TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY)));
        assertTrue(ex.getMessage().contains("Failed to encode TextToSpeechRequest"), ex.getMessage());
    }

    @Test
    void speechWrapsBodyReadFailureAsTransportException() {
        HttpTransport transport = req -> new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public InputStream body() {
                return new InputStream() {
                    public int read() throws IOException { throw new IOException("body read failed"); }
                };
            }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        AudioClientImpl client = build(transport, encodingCodec(), List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class,
                () -> client.speech(TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY)));
        assertTrue(ex.getMessage().contains("Failed to read binary response body"), ex.getMessage());
    }

    @Test
    void speechAsyncCompletesSuccessfully() throws Exception {
        byte[] audioBytes = new byte[]{1, 2, 3};
        HttpTransport transport = req -> binaryResponse(200, audioBytes);
        AudioClientImpl client = build(transport, encodingCodec(), List.of());
        CompletableFuture<byte[]> f = client.speechAsync(
                TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY));
        assertArrayEquals(audioBytes, f.get());
    }

    @Test
    void speechAsyncCompletesExceptionally() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, encodingCodec(), List.of());
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                client.speechAsync(TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY)).get());
        assertInstanceOf(FanarAuthenticationException.class, ex.getCause());
    }

    @Test
    void speechObservationOpensAndAttributesIncludeModel() {
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicInteger attrs = new AtomicInteger();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { attrs.incrementAndGet(); return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { }
            };
        };
        HttpTransport transport = req -> binaryResponse(200, new byte[]{0});
        AudioClientImpl client = new AudioClientImpl(
                BASE, encodingCodec(), () -> "t", List.of(), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        client.speech(TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY));

        assertEquals("fanar.audio.speech", opened.get());
        // model + method + url + status = 4 attributes
        assertTrue(attrs.get() >= 4);
    }

    @Test
    void speechReportsErrorOnObservationWhenInterceptorThrows() {
        AtomicInteger errored = new AtomicInteger();
        ObservabilityPlugin plugin = name -> errorObs(errored);
        Interceptor blowingUp = (req, ch) -> { throw new RuntimeException("boom"); };
        HttpTransport transport = req -> binaryResponse(200, new byte[]{0});
        AudioClientImpl client = new AudioClientImpl(
                BASE, encodingCodec(), () -> "t", List.of(blowingUp), transport,
                plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, () -> client.speech(
                TextToSpeechRequest.of(TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY)));
        assertEquals(1, errored.get());
    }

    @Test
    void speechRequestUsesTtsResponseFormatWavWhenSet() throws Exception {
        // Just a smoke: setting the optional response format should still produce a valid POST
        // with the same Accept: audio/* (server picks the actual content-type via the body field).
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return binaryResponse(200, new byte[]{0}); };
        AudioClientImpl client = build(transport, encodingCodec(), List.of());
        client.speech(new TextToSpeechRequest(
                TtsModel.FANAR_AURA_TTS_2, "hi", Voice.HARRY, TtsResponseFormat.WAV, null));
        assertEquals(Optional.of("audio/*"), captured.get().headers().firstValue("Accept"));
    }

    // --- transcribe (STT) ------------------------------------------------------------------

    @Test
    void transcribePostsMultipartToTranscriptionsEndpointWithJsonAccept() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        SpeechToTextResponse canned = new SpeechToTextResponse.Text("id-1", "hello");
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        SpeechToTextResponse result = build(transport, cannedTranscribeCodec(canned), List.of())
                .transcribe(new TranscriptionRequest(
                        new byte[]{1, 2, 3, 4}, "audio.wav", "audio/wav",
                        SttModel.FANAR_AURA_STT_1, SttFormat.TEXT));

        assertSame(canned, result);
        HttpRequest sent = captured.get();
        assertEquals("POST", sent.method());
        assertEquals("/v1/audio/transcriptions", sent.uri().getPath());
        String contentType = sent.headers().firstValue("Content-Type").orElseThrow();
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType);
        assertEquals(Optional.of("application/json"), sent.headers().firstValue("Accept"));

        String body = bodyOf(sent);
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav"), body);
        assertTrue(body.contains("name=\"model\"\r\n\r\nFanar-Aura-STT-1\r\n"), body);
        assertTrue(body.contains("name=\"format\"\r\n\r\ntext\r\n"), body);
    }

    @Test
    void transcribeOmitsFormatFieldWhenNotSet() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpTransport transport = req -> { captured.set(req); return httpResponse(200, "{}", Map.of()); };
        build(transport, cannedTranscribeCodec(new SpeechToTextResponse.Text("id", "x")), List.of())
                .transcribe(TranscriptionRequest.of(
                        new byte[]{1}, "audio.wav", "audio/wav", SttModel.FANAR_AURA_STT_LF_1));

        String body = bodyOf(captured.get());
        assertTrue(body.contains("name=\"model\"\r\n\r\nFanar-Aura-STT-LF-1\r\n"), body);
        assertFalse(body.contains("name=\"format\""), body);
    }

    @Test
    void transcribeMaps401ToAuthenticationException() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, cannedTranscribeCodec(null), List.of());
        assertThrows(FanarAuthenticationException.class, () -> client.transcribe(
                TranscriptionRequest.of(new byte[]{1}, "f.wav", "audio/wav", SttModel.FANAR_AURA_STT_1)));
    }

    @Test
    void transcribeWrapsCodecDecodeFailureAsTransportException() {
        FanarJsonCodec failingDecode = new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException { throw new IOException("decode"); }
            public void encode(OutputStream s, Object v) { /* unused */ }
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = build(transport, failingDecode, List.of());
        FanarTransportException ex = assertThrows(FanarTransportException.class, () -> client.transcribe(
                TranscriptionRequest.of(new byte[]{1}, "f.wav", "audio/wav", SttModel.FANAR_AURA_STT_1)));
        assertTrue(ex.getMessage().contains("Failed to decode SpeechToTextResponse"), ex.getMessage());
    }

    @Test
    void transcribeAsyncCompletesSuccessfully() throws Exception {
        SpeechToTextResponse canned = new SpeechToTextResponse.Text("id", "hi");
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = build(transport, cannedTranscribeCodec(canned), List.of());
        CompletableFuture<SpeechToTextResponse> f = client.transcribeAsync(
                TranscriptionRequest.of(new byte[]{1}, "f.wav", "audio/wav", SttModel.FANAR_AURA_STT_1));
        assertSame(canned, f.get());
    }

    @Test
    void transcribeAsyncCompletesExceptionally() {
        HttpTransport transport = req -> httpResponse(401, "{\"error\":{}}", Map.of());
        AudioClientImpl client = build(transport, cannedTranscribeCodec(null), List.of());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> client.transcribeAsync(TranscriptionRequest.of(
                        new byte[]{1}, "f.wav", "audio/wav", SttModel.FANAR_AURA_STT_1)).get());
        assertInstanceOf(FanarAuthenticationException.class, ex.getCause());
    }

    @Test
    void transcribeObservationOpensAndAttributesIncludeModel() {
        AtomicReference<String> opened = new AtomicReference<>();
        AtomicInteger attrs = new AtomicInteger();
        ObservabilityPlugin plugin = name -> {
            opened.set(name);
            return new ObservationHandle() {
                public ObservationHandle attribute(String k, Object v) { attrs.incrementAndGet(); return this; }
                public ObservationHandle event(String n) { return this; }
                public ObservationHandle error(Throwable t) { return this; }
                public ObservationHandle child(String c) { return this; }
                public Map<String, String> propagationHeaders() { return Map.of(); }
                public void close() { }
            };
        };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, cannedTranscribeCodec(new SpeechToTextResponse.Text("id", "x")), () -> "t",
                List.of(), transport, plugin, RetryPolicy.disabled(), Map.of(), null);
        client.transcribe(TranscriptionRequest.of(
                new byte[]{1}, "f.wav", "audio/wav", SttModel.FANAR_AURA_STT_1));
        assertEquals("fanar.audio.transcribe", opened.get());
        // model + method + url + status = 4 attributes
        assertTrue(attrs.get() >= 4);
    }

    @Test
    void transcribeReportsErrorOnObservationWhenInterceptorThrows() {
        AtomicInteger errored = new AtomicInteger();
        ObservabilityPlugin plugin = name -> errorObs(errored);
        Interceptor blowingUp = (req, ch) -> { throw new RuntimeException("boom"); };
        HttpTransport transport = req -> httpResponse(200, "{}", Map.of());
        AudioClientImpl client = new AudioClientImpl(
                BASE, cannedTranscribeCodec(null), () -> "t",
                List.of(blowingUp), transport, plugin, RetryPolicy.disabled(), Map.of(), null);
        assertThrows(RuntimeException.class, () -> client.transcribe(
                TranscriptionRequest.of(new byte[]{1}, "f.wav", "audio/wav", SttModel.FANAR_AURA_STT_1)));
        assertEquals(1, errored.get());
    }

    // --- null-arg + constructor null guards ------------------------------------------------

    @Test
    void allMethodsRejectNullArgs() {
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        AudioClientImpl client = build(transport, cannedListCodec(empty()), List.of());
        assertThrows(NullPointerException.class, () -> client.createVoice(null));
        assertThrows(NullPointerException.class, () -> client.createVoiceAsync(null));
        assertThrows(NullPointerException.class, () -> client.deleteVoice(null));
        assertThrows(NullPointerException.class, () -> client.deleteVoiceAsync(null));
        assertThrows(NullPointerException.class, () -> client.speech(null));
        assertThrows(NullPointerException.class, () -> client.speechAsync(null));
        assertThrows(NullPointerException.class, () -> client.transcribe(null));
        assertThrows(NullPointerException.class, () -> client.transcribeAsync(null));
    }

    @Test
    void constructorRejectsNulls() {
        FanarJsonCodec codec = cannedListCodec(empty());
        HttpTransport transport = req -> httpResponse(200, "", Map.of());
        ObservabilityPlugin obs = ObservabilityPlugin.noop();
        RetryPolicy rp = RetryPolicy.disabled();
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(null, codec, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, null, () -> "t", List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, codec, null, List.of(), transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, codec, () -> "t", null, transport, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, codec, () -> "t", List.of(), null, obs, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, codec, () -> "t", List.of(), transport, null, rp, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, null, Map.of(), null));
        assertThrows(NullPointerException.class, () ->
                new AudioClientImpl(BASE, codec, () -> "t", List.of(), transport, obs, rp, null, null));
    }

    // --- helpers

    private static AudioClientImpl build(HttpTransport transport, FanarJsonCodec codec,
                                         List<Interceptor> interceptors) {
        return build(transport, codec, interceptors, "stub-token");
    }

    private static AudioClientImpl build(HttpTransport transport, FanarJsonCodec codec,
                                         List<Interceptor> interceptors, String token) {
        return new AudioClientImpl(
                BASE, codec, () -> token, interceptors,
                transport, ObservabilityPlugin.noop(), RetryPolicy.disabled(), Map.of(), null);
    }

    private static VoiceResponse empty() {
        return new VoiceResponse(List.of());
    }

    /** Codec used by listVoices tests — decodes whatever is read into the canned response. */
    private static FanarJsonCodec cannedListCodec(VoiceResponse canned) {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes();
                return t.cast(canned);
            }
            public void encode(OutputStream s, Object v) { /* unused for list */ }
        };
    }

    /** Codec used by transcribe tests — decodes the response into the canned variant. */
    private static FanarJsonCodec cannedTranscribeCodec(SpeechToTextResponse canned) {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) throws IOException {
                s.readAllBytes();
                return t.cast(canned);
            }
            public void encode(OutputStream s, Object v) { /* unused — multipart hand-built */ }
        };
    }

    /** Codec used by create / delete tests — should never be invoked. */
    private static FanarJsonCodec throwOnAnyCodec() {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) {
                throw new AssertionError("decode should not be called for non-decoding ops");
            }
            public void encode(OutputStream s, Object v) {
                throw new AssertionError("encode should not be called for multipart ops");
            }
        };
    }

    private static ObservationHandle errorObs(AtomicInteger errored) {
        return new ObservationHandle() {
            public ObservationHandle attribute(String k, Object v) { return this; }
            public ObservationHandle event(String n) { return this; }
            public ObservationHandle error(Throwable t) { errored.incrementAndGet(); return this; }
            public ObservationHandle child(String c) { return this; }
            public Map<String, String> propagationHeaders() { return Map.of(); }
            public void close() { }
        };
    }

    /** Codec that encodes via marker but never decodes. Used for speech tests. */
    private static FanarJsonCodec encodingCodec() {
        return new FanarJsonCodec() {
            public <T> T decode(InputStream s, Class<T> t) {
                throw new AssertionError("decode should not be called for binary-response op");
            }
            public void encode(OutputStream s, Object v) throws IOException {
                s.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static HttpResponse<InputStream> binaryResponse(int status, byte[] bytes) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of("Content-Type", List.of("audio/mpeg")), (a, b) -> true);
            }
            public InputStream body() { return new ByteArrayInputStream(bytes); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static HttpResponse<InputStream> httpResponse(int status, String body, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(headers, (a, b) -> true); }
            public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://t"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher bp = request.bodyPublisher().orElseThrow();
        AtomicReference<byte[]> buf = new AtomicReference<>(new byte[0]);
        CountDownLatch done = new CountDownLatch(1);
        bp.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) {
                byte[] curr = buf.get();
                byte[] next = new byte[curr.length + b.remaining()];
                System.arraycopy(curr, 0, next, 0, curr.length);
                b.get(next, curr.length, b.remaining());
                buf.set(next);
            }
            public void onError(Throwable t) { done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        done.await(1, TimeUnit.SECONDS);
        return new String(buf.get(), StandardCharsets.UTF_8);
    }
}
