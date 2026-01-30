package revealtogether.websockets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests.
 * Provides Redis Testcontainer and Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestContainersConfig.class)
@Testcontainers
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired(required = false)
    protected WebTestClient webTestClient;
}
