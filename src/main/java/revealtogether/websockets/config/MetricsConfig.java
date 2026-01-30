package revealtogether.websockets.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import revealtogether.websockets.repository.RedisRepository;

@Configuration
public class MetricsConfig {

    @Bean
    public Gauge activeSessionsGauge(MeterRegistry registry, RedisRepository redisRepository) {
        return Gauge.builder("revealtogether.sessions.active", redisRepository, repo -> repo.getActiveSessions().size())
                .description("Number of active reveal sessions")
                .register(registry);
    }

    @Bean
    public Counter votesCounter(MeterRegistry registry) {
        return Counter.builder("revealtogether.votes.total")
                .description("Total number of votes cast")
                .register(registry);
    }

    @Bean
    public Counter chatMessagesCounter(MeterRegistry registry) {
        return Counter.builder("revealtogether.chat.messages.total")
                .description("Total number of chat messages sent")
                .register(registry);
    }

    @Bean
    public Counter revealsCompletedCounter(MeterRegistry registry) {
        return Counter.builder("revealtogether.reveals.completed")
                .description("Total number of reveals completed")
                .register(registry);
    }
}
