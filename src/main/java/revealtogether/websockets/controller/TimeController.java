package revealtogether.websockets.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * WP4 — server clock for offset sampling.
 *
 * Clients take several samples and keep the one with the lowest round-trip to
 * estimate their offset. This is what makes a countdown agree across devices
 * without trusting any device's clock — and it is only ever a display aid: the
 * release decision is the server's alone (WP5/WP8).
 */
@RestController
@RequestMapping("/api")
public class TimeController {

    @GetMapping("/time")
    public ResponseEntity<Map<String, Object>> now() {
        Instant now = Instant.now();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "serverTime", now.toString(),
                        "epochMillis", now.toEpochMilli()
                ));
    }
}
