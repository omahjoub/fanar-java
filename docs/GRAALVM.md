# GraalVM native-image — a learning-first walkthrough

This guide is structured for someone meeting `native-image` for the first time. It builds
a mental model, then walks every command needed to reproduce the SDK's GraalVM smoke
locally, then explains the iteration loop and the troubleshooting paths. If you've used
`native-image` before, the [TL;DR](#tl-dr) section near the top is enough.

---

## What this validates

The README promises *"GraalVM native-image workloads — reflection-free by design, ready
for serverless and edge."* The `e2e-graalvm` module backs that promise: every PR build
compiles the SDK to a single native binary and verifies the binary works. If a new
reflective access creeps in without metadata, the build fails before it reaches users.

What "works" means concretely:

- The binary starts in ~30 ms (vs ~3 s for the JVM-mode equivalent).
- It uses ~50 MB RSS (vs ~500 MB).
- Every Fanar domain (chat, models, tokens, moderations, translations, poems, images,
  audio) decodes its responses correctly.
- Every observability adapter (SLF4J, OTel, Micrometer, the composite that fans them out)
  runs without crashing.
- Every typed Fanar exception (auth, timeout, rate limit, …) is reachable from the
  binary's exception-mapping path.

---

## TL;DR

You have a GraalVM JDK installed at `$JAVA_HOME`.

```bash
# Build the native binary (~45 s first time, faster later).
JAVA_HOME=$JAVA_HOME ./mvnw -Pnative -pl e2e-graalvm -am package

# Verify it works without an API key:
./e2e-graalvm/target/fanar-graalvm-smoke --self-test

# Run the live walk against the real Fanar API:
FANAR_API_KEY=… ./e2e-graalvm/target/fanar-graalvm-smoke
```

If you've never built a native image before, keep reading.

---

## The two-mode mental model

GraalVM is a JDK distribution that adds an extra tool — `native-image`. With it you have
**two ways** to run your code:

```
JIT mode (just-in-time)               AOT mode (ahead-of-time)
─────────────────────                 ──────────────────────────
java -jar app.jar                     ./fanar-graalvm-smoke
   │                                     │
   ▼                                     ▼
Reads .class files from the jar       Runs a binary that already
at startup, JIT-compiles them         contains compiled native
to native code as they execute.       code for every reachable
Behaves like any other JVM.           method, plus an embedded
                                      tiny VM (SubstrateVM).

  Startup: ~3 s                          Startup: ~30 ms
  Memory:  ~500 MB                       Memory:  ~50 MB
  Picks up source changes               Frozen at build time
  every time you rebuild?                once compiled —
  Yes, after `mvn package`.              you must rerun
                                         `native-image`
                                         to see changes.
```

**The single most important consequence**, and the one that bites every newcomer once:

> If you edit `Main.java` and re-run the binary, **you will not see your change.** The
> binary is the binary. To pick up source changes, rebuild with `-Pnative`, which reruns
> `native-image` (~45 s).

**Practical workflow:** iterate in JIT mode for the fast feedback loop, switch to native
only when you specifically want to validate AOT behavior.

| You want to… | Use this |
|---|---|
| Iterate on `Main.java` quickly | JIT — `./mvnw -pl e2e-graalvm -am package` then `java -jar e2e-graalvm/target/fanar-java-e2e-graalvm-*.jar --self-test` |
| Verify the SDK still compiles to a working native binary | AOT — `./mvnw -Pnative -pl e2e-graalvm -am package` then `./e2e-graalvm/target/fanar-graalvm-smoke --self-test` |
| Bootstrap reachability metadata for new reflective code | JIT under the tracing agent (see [Bootstrap loop](#bootstrap-loop)) |
| Run the live smoke against real Fanar | Either mode works; native gives you the real startup-time numbers |

---

## The reachability problem

`native-image` performs **closed-world static analysis**. It walks your call graph from
`main`, compiles every reachable method, and discards everything else. That's how it
gets the small binary and fast startup — there's no JIT, no class loading, no
interpreter at runtime.

The downside: anything that's not statically reachable doesn't survive. This breaks at
runtime if your code uses:

- **Reflection** (`Class.forName`, `getDeclaredMethod`, `getRecordComponents`)
- **`ServiceLoader`**
- **Dynamic proxies**
- **Resource lookups** (`Class.getResourceAsStream`)
- **Some method-handle / lambda paths** (rare)

Any of those need to be declared up front in **reachability metadata** files at
`META-INF/native-image/<group>/<artifact>/`. The SDK already ships metadata for the
parts that need it; this guide explains how to add more when you write code that
introduces new reflective access.

---

## Setup (one time, ~5 minutes)

You need a GraalVM-distribution JDK. Three options, ranked by ease:

**1. SDKMAN (easiest):**

```bash
curl -s "https://get.sdkman.io" | bash      # if not already installed
sdk install java 21-graalce
sdk use java 21-graalce
```

After this, `java`, `javac`, and `native-image` are all on `PATH` and `JAVA_HOME`
points at GraalVM. Skip to [Verify](#verify).

**2. Manual download:**

Download a tarball from <https://www.graalvm.org/downloads/>, extract it, then:

```bash
# macOS:
export JAVA_HOME="/path/to/graalvm-jdk-21.0.x/Contents/Home"
# Linux:
export JAVA_HOME="/path/to/graalvm-jdk-21.0.x"
export PATH="$JAVA_HOME/bin:$PATH"
```

You can also leave `JAVA_HOME` unset globally and just prefix every Maven command with
it — the SDK's `./mvnw` honors `JAVA_HOME`:

```bash
JAVA_HOME=/path/to/graalvm/Contents/Home ./mvnw …
```

This is the right pattern if you have multiple JDKs installed and don't want GraalVM as
your default.

**3. Native macOS / Homebrew:** install via SDKMAN as above. Don't use the system Oracle
JDK — it doesn't include `native-image`.

### macOS-only extra step

`native-image` shells out to the system linker. On macOS that means Xcode Command Line
Tools must be installed (you usually already have them if you've ever run `git`):

```bash
xcode-select -p              # should print a path; if not:
xcode-select --install       # ~10-minute one-time download
```

You may also be prompted to accept the Xcode license the first time you compile a
native image:

```bash
sudo xcodebuild -license accept    # one time, only if prompted
```

### Verify

```bash
java --version            # should mention "GraalVM"
which native-image        # should resolve to a binary inside JAVA_HOME
```

If both look good, you're done with setup.

---

## First run — quickest path to a working binary

```bash
# 1. Build the native binary. ~45 s first time, ~30 s after.
./mvnw -Pnative -pl e2e-graalvm -am package

# 2. Run the offline smoke. Should take well under a second.
./e2e-graalvm/target/fanar-graalvm-smoke --self-test
```

Expected output:

```
[main] DEBUG fanar.smoke.op - event=smoke_event
[main] ERROR fanar.smoke.op - failed after 0ms attrs={k=v}
java.lang.IllegalStateException: smoke-error
        …
self-test OK: 9 decode probes + 9 encode probes, 4 obs plugins exercised, wire interceptor instantiated
```

The `smoke-error` lines are intentional — the self-test deliberately calls
`error(throwable)` on each obs plugin to exercise the error path. The final
`self-test OK` line is the success signal.

If you have a `FANAR_API_KEY` set, the live walk hits every domain endpoint with the
same binary:

```bash
FANAR_API_KEY=… ./e2e-graalvm/target/fanar-graalvm-smoke
```

You'll see for each domain: a wire-log line (`--> POST …` / `<-- 200 …`), a per-operation
SLF4J observation summary, and the probe's own one-liner with the response id /
counts. Domains your token doesn't have access to surface as typed exceptions
(`FanarAuthorizationException`, `FanarRateLimitException`, `FanarTimeoutException`) and
the walk continues past them — that's the error-path validation.

---

## The iteration loop

You're editing `Main.java` (or any code under `e2e-graalvm/`). Two scenarios:

### "I want fast feedback while I write code" → JIT mode

```bash
./mvnw -pl e2e-graalvm -am package -DskipTests
java -jar e2e-graalvm/target/fanar-java-e2e-graalvm-0.1.0-SNAPSHOT.jar --self-test
```

Picks up source changes after every `mvn package`. ~5 s round-trip. Use this for 99%
of development.

### "I want to verify the binary still works under AOT" → native mode

```bash
./mvnw -Pnative -pl e2e-graalvm -am package -DskipTests
./e2e-graalvm/target/fanar-graalvm-smoke --self-test
```

~45 s round-trip. Reach for this when:

- You added code that does reflection (records, `ServiceLoader`, resource lookups) —
  see [Bootstrap loop](#bootstrap-loop) for the metadata workflow.
- You changed something low-level (HTTP transport, codec, interceptor chain) and want
  to make sure native still works.
- You're about to push a PR — CI will rebuild native anyway, but the local round-trip
  catches it ~3 minutes faster.

### "I changed `Main.java` but the binary still shows the old behavior!"

You forgot to rebuild the native binary. The previous binary is **frozen** at the source
state from the last `-Pnative` build. Rebuild:

```bash
./mvnw -Pnative -pl e2e-graalvm -am package -DskipTests
```

Then re-run the binary. Now you'll see your change.

This is the single most common newcomer mistake. If you find yourself baffled by
unchanged output, your first move should be: did I rerun the `-Pnative` build?

---

## Bootstrap loop — adding metadata when you add reflective code

When you add a new code path that uses reflection (the most common cases: a new domain
record that Jackson introspects, a new SPI consumed via `ServiceLoader`, a new resource
lookup), you need to extend the SDK's reachability metadata. The SDK already ships
metadata for what's there today; this is how you grow it.

The mechanism: a **tracing agent** that ships with GraalVM, attached to a JIT-mode
run. It intercepts every reflective access at runtime and dumps JSON files describing
what it saw.

### Step 1 — exercise the new code path in `Main.selfTest()`

Add a new probe method that uses the new reflective surface — typically encoding or
decoding a new record, or invoking a new SPI. The smoke method drives the agent's
recording, so anything you don't call here won't be captured.

### Step 2 — build the JIT fat jar

```bash
./mvnw -pl e2e-graalvm -am package -DskipTests
```

The `e2e-graalvm/target/fanar-java-e2e-graalvm-*.jar` file is bundled (via
`maven-shade-plugin`) so that `java -jar` works without a separate classpath. The agent
will read this jar.

### Step 3 — run under the tracing agent

```bash
mkdir -p /tmp/fanar-graalvm-bootstrap
java -agentlib:native-image-agent=config-output-dir=/tmp/fanar-graalvm-bootstrap \
     -jar e2e-graalvm/target/fanar-java-e2e-graalvm-*.jar --self-test
```

The agent writes six JSON files into `/tmp/fanar-graalvm-bootstrap/`:

| File | Holds |
|---|---|
| `reflect-config.json` | Reflective accesses (`getDeclaredMethod`, record components, …) |
| `resource-config.json` | Resource lookups (`Class.getResource`, `Class.getResourceAsStream`) |
| `serialization-config.json` | Java serialization targets |
| `proxy-config.json` | Dynamic proxy interfaces |
| `jni-config.json` | JNI lookups (rarely matters for pure-Java apps) |
| `predefined-classes-config.json` | Classes loaded at runtime via `ClassLoader.defineClass` |

Most are empty for a typical Java SDK. The interesting ones are `reflect-config.json`
and `resource-config.json`.

### Step 4 — diff against committed metadata

```bash
diff /tmp/fanar-graalvm-bootstrap/reflect-config.json \
     core/src/main/resources/META-INF/native-image/qa.fanar/fanar-core/reflect-config.json
```

New entries are reflective accesses your new probe triggered. Decide which module owns
them:

- Entries about `qa.fanar.core.*` records →
  `core/src/main/resources/META-INF/native-image/qa.fanar/fanar-core/`
- Entries about `qa.fanar.json.jackson3.*` →
  `json-jackson3/src/main/resources/META-INF/native-image/qa.fanar/fanar-json-jackson3/`
- Entries about `qa.fanar.obs.*` or `qa.fanar.interceptor.*` → that adapter module's
  metadata directory (create it if it doesn't exist).

The metadata splits matter: `core` users who don't pull in `json-jackson3` shouldn't
ship Jackson hints. In practice, for a quick bootstrap pass it's acceptable to dump
everything into `core`'s metadata and split afterwards.

### Step 5 — recompile native, run, verify

```bash
./mvnw -Pnative -pl e2e-graalvm -am package -DskipTests
./e2e-graalvm/target/fanar-graalvm-smoke --self-test
```

If the binary now starts cleanly, the metadata is correct. If it crashes with
`UnsupportedFeatureError` or `ClassNotFoundException`, exercise the failing path under
the agent again and merge the new entries.

---

## CI integration

`.github/workflows/graalvm.yml` has two jobs:

- **`native-smoke`** — runs on every PR that touches `core`, `json-jackson3`,
  `e2e-graalvm`, the parent pom, or this workflow file. Builds the native binary and
  runs `--self-test`. ~3 minutes on free-tier runners. PR-level regression gate.
- **`bootstrap`** — `workflow_dispatch` only, with `action: bootstrap`. Runs the JIT
  fat jar under the tracing agent against the live API (uses the `FANAR_API_KEY` repo
  secret), uploads the captured `metadata-out/` directory as a workflow artifact.

You can run the `bootstrap` job from the GitHub Actions UI without ever installing
GraalVM locally — useful when the metadata depends on a server-shape you can't easily
replicate offline. Download the artifact, split entries by module, commit.

---

## Troubleshooting

### "I edited code but the binary's behavior didn't change"

You didn't rebuild the native binary. Run:

```bash
./mvnw -Pnative -pl e2e-graalvm -am package -DskipTests
```

The new binary is in `e2e-graalvm/target/fanar-graalvm-smoke`. **This is the most common
newcomer mistake.** It's not GraalVM's fault — it's how AOT compilation works: source
isn't read at runtime, only at build time.

### `UnsupportedFeatureError: Record components not available for record class …`

Jackson 3 (and Jackson 2 with certain configurations) introspects records via
`Class.getRecordComponents()`. The fix is a `reflect-config.json` entry with
`"allRecordComponents": true` for the offending record. The bootstrap loop above catches
this automatically; usually you've added a new record or a new code path that encodes /
decodes a record the smoke didn't previously touch.

### `ClassNotFoundException` at runtime in code that compiled fine

A reflective `Class.forName(string)` was statically un-analyzable. Either declare the
target class in `reflect-config.json` or — better — refactor to a direct type reference
so the analyzer can prove reachability without metadata.

### `java.util.ServiceLoader` returns no providers

The provider's `META-INF/services/<interface>` file isn't being included. Add it to
`resource-config.json`:

```json
{
  "resources": {
    "includes": [
      { "pattern": "\\QMETA-INF/services/qa.fanar.core.spi.FanarJsonCodec\\E" }
    ]
  }
}
```

The SDK's existing metadata already lists the `FanarJsonCodec` ServiceLoader file plus
SLF4J's, OTel's `ContextStorageProvider`, and `java.time.zone.ZoneRulesProvider`. If
you need to add a new ServiceLoader interface, this is where it goes.

### Logs / properties files aren't picked up by the binary

If your code reads a config file from the classpath (`Class.getResourceAsStream`), that
file is **not** automatically bundled into the binary. Either:

- Run the tracing agent so it sees the read and writes the entry to
  `resource-config.json` (the SDK already covers `simplelogger.properties` for the
  smoke), or
- Hand-add the entry: `{ "pattern": "\\Qmy/config.properties\\E" }`.

### Compile-time "fallback class initialization" warnings

`native-image` computes a class-initialization plan. Some library classes can't be
initialized at build time and are deferred to runtime — a warning prints, but the
binary still works. **Usually safe to ignore.** Check the warning's class name; if your
code path doesn't actually hit it, the warning is informational.

### "Peak RSS: 4 GB" during compilation

That's normal for `native-image`. The static analyzer holds the whole call graph in
memory. Nothing to worry about unless your machine doesn't have enough RAM (free-tier
GitHub runners with 7 GB handle the SDK's scale fine).

### macOS: `xcrun: error: …`

Xcode Command Line Tools aren't installed. Run `xcode-select --install`.

---

## What `--self-test` exercises

The self-test runs entirely offline and walks the full reflective surface of the SDK so
the bootstrap pass picks up every metadata gap in one go. As of the current commit:

- **Decode probes** for every domain — chat, models, tokens, moderations, translations,
  poems, images, audio voices, audio STT (both `text` and `json` sealed variants).
- **Encode probes** for every request record — `ChatRequest`, `TokenizationRequest`,
  `SafetyFilterRequest`, `TranslationRequest`, `PoemGenerationRequest`,
  `ImageGenerationRequest`, `TextToSpeechRequest`, `TranscriptionRequest`,
  `CreateVoiceRequest`. The encode path uses Jackson 3's serializer factory which
  independently introspects records, so decode coverage alone doesn't cover it.
- **All four observability plugins** instantiated and dispatched through one full event
  lifecycle: `Slf4jObservabilityPlugin`, `OpenTelemetryObservabilityPlugin` (with
  `OpenTelemetry.noop()`), `MicrometerObservabilityPlugin`, and the composite produced
  by `ObservabilityPlugin.compose(...)`.
- **`WireLoggingInterceptor`** instantiated at every level (`NONE` / `BASIC` / `HEADERS`
  / `BODY`).

If you add a new code path that uses reflection (a new domain DTO, a new SPI consumer,
a new dynamic proxy), extend the corresponding section of `Main.selfTest()` and rerun
the [Bootstrap loop](#bootstrap-loop).

## What live mode exercises

The default-args invocation (`./fanar-graalvm-smoke` with `FANAR_API_KEY` set) walks
**every** domain over the live HTTP transport with the full observability stack
(SLF4J + OTel + Micrometer composed) and the wire-logging interceptor at `BASIC` level
attached. Each probe is wrapped so a typed `FanarException` (auth gating, rate limiting,
timeouts) is logged and the walk continues — failure to authorize on one domain
shouldn't stop us from exercising the next under native. Includes one async probe to
exercise the virtual-thread async wrapper.

## Limits of what this catches

- **Only Jackson 3 is validated.** Jackson 2 lives in a separate classpath island and
  has its own reflective surfaces; a parallel `e2e-graalvm-jackson2` module (or a second
  `Main`) would be needed to validate it.
- **OTel propagation under a real SDK is not exercised.** The smoke uses
  `OpenTelemetry.noop()` so the plugin's internals run, but the W3C `traceparent`
  injection path uses an SDK propagator that's only exercised in `obs-otel`'s unit
  tests.
- **No live HTTP in the offline self-test.** The PR-time CI gate is offline; live mode
  remains optional via `workflow_dispatch` or local invocation with `FANAR_API_KEY`.
- **No Spring Boot AOT or Quarkus integration.** The SDK shipping native-image-clean is
  necessary but not sufficient for those frameworks; their own AOT processors fold the
  SDK's metadata into application-level hints. Validation through a real Spring Boot or
  Quarkus sample is part of the framework-adapter milestone.
