package revealtogether.websockets.command;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * WP2 — applies the command envelope to every request.
 *
 * 1. Trace id: taken from X-Trace-Id or generated; pushed to MDC so every log
 *    line carries it, and echoed on the response.
 * 2. Idempotency: for mutations carrying Idempotency-Key, replays the stored
 *    response instead of re-executing (Idempotency-Replayed: true), and rejects
 *    a reused key with a different body as IDEMPOTENCY_CONFLICT.
 * 3. Client context (platform/version/locale) into MDC for debugging.
 *
 * Fully additive: a request with none of these headers behaves exactly as
 * before. Idempotency can be disabled with app.command.idempotency-enabled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CommandEnvelopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CommandEnvelopeFilter.class);
    public static final String ATTR_ENVELOPE = "rt.commandEnvelope";

    private final IdempotencyService idempotencyService;
    private final boolean idempotencyEnabled;

    public CommandEnvelopeFilter(
            IdempotencyService idempotencyService,
            @Value("${app.command.idempotency-enabled:true}") boolean idempotencyEnabled
    ) {
        this.idempotencyService = idempotencyService;
        this.idempotencyEnabled = idempotencyEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // SockJS/STOMP and health checks are high-volume and not commands.
        return path.startsWith("/ws") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CommandEnvelope env = CommandEnvelope.from(request);
        request.setAttribute(ATTR_ENVELOPE, env);

        MDC.put("traceId", env.traceId());
        if (env.clientPlatform() != null) MDC.put("platform", env.clientPlatform());
        if (env.clientVersion() != null) MDC.put("clientVersion", env.clientVersion());
        response.setHeader(CommandEnvelope.H_TRACE_ID, env.traceId());

        try {
            boolean idempotent = idempotencyEnabled
                    && env.idempotencyKey() != null
                    && isMutation(request.getMethod());
            if (!idempotent) {
                chain.doFilter(request, response);
                return;
            }
            handleIdempotent(request, response, chain, env);
        } finally {
            MDC.clear();
        }
    }

    private void handleIdempotent(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain, CommandEnvelope env)
            throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // Buffer the body so it can be hashed here AND still read downstream.
        CachedBodyRequestWrapper req = new CachedBodyRequestWrapper(request);
        String requestHash = IdempotencyService.hashBody(req.getBody());

        Optional<IdempotencyService.StoredResponse> existing = idempotencyService.find(env, method, path);
        if (existing.isPresent()) {
            IdempotencyService.StoredResponse stored = existing.get();

            if (!requestHash.equals(stored.requestHash())) {
                writeJson(response, HttpStatus.CONFLICT.value(), String.format(
                        "{\"code\":\"IDEMPOTENCY_CONFLICT\",\"message\":\"This Idempotency-Key was used with a different request body\",\"traceId\":\"%s\"}",
                        env.traceId()));
                return;
            }
            if (stored.status() > 0) {
                log.info("Idempotent replay: {} {} key={}", method, path,
                        CommandEnvelope.sha256Short(env.idempotencyKey()));
                response.setHeader(CommandEnvelope.H_REPLAYED, "true");
                writeJson(response, stored.status(), stored.body());
                return;
            }
            // status 0 = claimed but still in flight (concurrent duplicate).
            writeJson(response, HttpStatus.CONFLICT.value(), String.format(
                    "{\"code\":\"IDEMPOTENCY_CONFLICT\",\"message\":\"An identical request is currently in flight\",\"traceId\":\"%s\"}",
                    env.traceId()));
            return;
        }

        // Atomic claim. find() above is a fast path, not the guard — without
        // honouring this result two concurrent duplicates would both execute.
        if (!idempotencyService.tryClaim(env, method, path, requestHash)) {
            Optional<IdempotencyService.StoredResponse> raced = idempotencyService.find(env, method, path);
            if (raced.isPresent() && raced.get().status() > 0
                    && requestHash.equals(raced.get().requestHash())) {
                response.setHeader(CommandEnvelope.H_REPLAYED, "true");
                writeJson(response, raced.get().status(), raced.get().body());
                return;
            }
            writeJson(response, HttpStatus.CONFLICT.value(), String.format(
                    "{\"code\":\"IDEMPOTENCY_CONFLICT\",\"message\":\"An identical request is currently in flight\",\"traceId\":\"%s\"}",
                    env.traceId()));
            return;
        }

        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(req, res);
            completed = true;
            String body = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
            idempotencyService.store(env, method, path, requestHash, res.getStatus(), body);
        } finally {
            if (!completed) idempotencyService.release(env, method, path);
            res.copyBodyToResponse();
        }
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (body != null && !body.isEmpty()) {
            response.getWriter().write(body);
            response.getWriter().flush();
        }
    }
}
