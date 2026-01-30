package revealtogether.websockets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Application Context Tests")
class WebsocketsApplicationTests extends BaseIntegrationTest {

    @Test
    @DisplayName("Should load application context successfully")
    void contextLoads() {
        // Context loads successfully if this test passes
        assertThat(true).isTrue();
    }
}
