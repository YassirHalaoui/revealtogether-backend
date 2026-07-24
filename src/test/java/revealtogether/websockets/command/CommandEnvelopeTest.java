package revealtogether.websockets.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import revealtogether.websockets.BaseIntegrationTest;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP2 acceptance: "double-submitting any mutation with the same key produces
 * exactly one effect; all requests traceable by trace id".
 */
@DisplayName("WP2 Command Envelope")
class CommandEnvelopeTest extends BaseIntegrationTest {

    // Bound to the live port, matching RevealControllerTest — the inherited
    // field is only populated when WebFlux test autoconfig is present.
    private org.springframework.test.web.reactive.server.WebTestClient webTestClient;

    @org.junit.jupiter.api.BeforeEach
    void bindClient() {
        webTestClient = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(20))
                .build();
    }

    @Autowired
    private IdempotencyService idempotencyService;

    private String createBody() {
        return String.format("""
                {"ownerId":"envelope-test","gender":"girl","revealTime":"%s",
                 "motherName":"Ann","fatherName":"Bob","paymentStatus":"completed"}""",
                Instant.now().plusSeconds(7200).toString());
    }

    @Test
    @DisplayName("Trace id is echoed back, and generated when the client omits it")
    void traceIdEchoedAndGenerated() {
        String mine = "t_client_supplied_1";
        webTestClient.get().uri("/api/session/{id}/state", UUID.randomUUID())
                .header(CommandEnvelope.H_TRACE_ID, mine)
                .exchange()
                .expectHeader().valueEquals(CommandEnvelope.H_TRACE_ID, mine);

        webTestClient.get().uri("/api/session/{id}/state", UUID.randomUUID())
                .exchange()
                .expectHeader().value(CommandEnvelope.H_TRACE_ID, v ->
                        assertThat(v).isNotBlank().startsWith("t_"));
    }

    @Test
    @DisplayName("Same key + same body → one effect, replayed response, marked as replay")
    void duplicateCommandProducesOneEffect() {
        String key = UUID.randomUUID().toString();
        String body = createBody();

        String firstId = webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().doesNotExist(CommandEnvelope.H_REPLAYED)
                .expectBody(Map.class)
                .returnResult().getResponseBody().get("sessionId").toString();

        String replayId = webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(CommandEnvelope.H_REPLAYED, "true")
                .expectBody(Map.class)
                .returnResult().getResponseBody().get("sessionId").toString();

        // Exactly one effect: the replay returns the SAME reveal, not a new one.
        assertThat(replayId).isEqualTo(firstId);
    }

    @Test
    @DisplayName("Same key + different body → 409 IDEMPOTENCY_CONFLICT (client bug, not silent replay)")
    void sameKeyDifferentBodyConflicts() {
        String key = UUID.randomUUID().toString();

        webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .bodyValue(createBody())
                .exchange()
                .expectStatus().isCreated();

        webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .bodyValue(createBody().replace("\"girl\"", "\"boy\""))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("Different actors reusing one key are independent")
    void keysAreScopedPerActor() {
        String key = UUID.randomUUID().toString();
        String body = createBody();

        String a = webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .header(CommandEnvelope.H_PARTICIPANT_TOKEN, "pt_actor_a")
                .bodyValue(body).exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("sessionId").toString();

        String b = webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .header(CommandEnvelope.H_PARTICIPANT_TOKEN, "pt_actor_b")
                .bodyValue(body).exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("sessionId").toString();

        assertThat(a).isNotEqualTo(b); // separate actors → separate effects
    }

    @Test
    @DisplayName("No Idempotency-Key → unchanged behaviour (backwards compatible)")
    void withoutKeyBehaviourUnchanged() {
        String body = createBody();
        String one = webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("sessionId").toString();
        String two = webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("sessionId").toString();
        assertThat(one).isNotEqualTo(two); // no key = no dedup, exactly as today
    }

    @Test
    @DisplayName("Concurrent duplicates: at most one succeeds, the rest are replay or in-flight conflict")
    void concurrentDuplicatesProduceOneEffect() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = createBody();
        int threads = 6;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> createdIds = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var result = webTestClient.post().uri("/api/reveals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer concurrency-actor")
                            .bodyValue(body)
                            .exchange()
                            .expectBody(Map.class)
                            .returnResult();
                    Object id = result.getResponseBody() == null ? null : result.getResponseBody().get("sessionId");
                    if (result.getStatus().value() == 201 && id != null) createdIds.add(id.toString());
                } catch (Exception ignored) {
                    // conflicts surface as non-201; counted by absence below
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // The guarantee: never two DIFFERENT reveals from one key.
        assertThat(createdIds).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Failed commands are not cached — the same key can be retried")
    void failuresAreRetryable() {
        String key = UUID.randomUUID().toString();
        String invalid = "{\"ownerId\":\"\",\"gender\":\"nope\"}";

        webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .bodyValue(invalid).exchange()
                .expectStatus().is4xxClientError();

        // Same key, now a valid body: must execute rather than replay the failure.
        webTestClient.post().uri("/api/reveals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommandEnvelope.H_IDEMPOTENCY_KEY, key)
                .bodyValue(createBody()).exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("Envelope parses headers and hashes actor identity rather than storing it")
    void envelopeParsingAndActorHashing() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(CommandEnvelope.H_EXPECTED_VERSION, "42");
        request.addHeader(CommandEnvelope.H_CLIENT_PLATFORM, "ios");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer super-secret-token");

        CommandEnvelope env = CommandEnvelope.from(request);
        assertThat(env.expectedVersion()).isEqualTo(42L);
        assertThat(env.clientPlatform()).isEqualTo("ios");
        assertThat(env.traceId()).startsWith("t_");
        assertThat(env.actorKey()).startsWith("a:").doesNotContain("super-secret-token");

        // Malformed version behaves as absent instead of failing the request.
        var bad = new org.springframework.mock.web.MockHttpServletRequest();
        bad.addHeader(CommandEnvelope.H_EXPECTED_VERSION, "not-a-number");
        assertThat(CommandEnvelope.from(bad).expectedVersion()).isNull();
    }
}
