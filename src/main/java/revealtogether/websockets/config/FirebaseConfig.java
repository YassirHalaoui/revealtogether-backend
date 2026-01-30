package revealtogether.websockets.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.cloud.firestore.Firestore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    // For production (Railway): base64-encoded credentials via env var
    @Value("${app.firebase.credentials:}")
    private String firebaseCredentials;

    // For localhost: JSON file in resources folder
    @Value("${app.firebase.credentials-file:}")
    private String firebaseCredentialsFile;

    @PostConstruct
    public void initialize() {
        // Debug: Log what env vars we're seeing
        log.info("FIREBASE_CREDENTIALS env var length: {}",
                 firebaseCredentials != null ? firebaseCredentials.length() : "null");
        log.info("FIREBASE_CREDENTIALS_FILE: {}", firebaseCredentialsFile);

        try {
            GoogleCredentials credentials = loadCredentials();
            if (credentials == null) {
                log.warn("Firebase credentials not configured. Firebase features will be disabled.");
                return;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                log.info("Firebase initialized successfully");
            }
        } catch (IOException e) {
            log.error("Failed to initialize Firebase", e);
            throw new RuntimeException("Failed to initialize Firebase", e);
        }
    }

    private GoogleCredentials loadCredentials() throws IOException {
        // Priority 1: Base64-encoded env var (for Railway/production)
        if (firebaseCredentials != null && !firebaseCredentials.isBlank()) {
            log.info("Loading Firebase credentials from environment variable");
            byte[] decodedCredentials = Base64.getDecoder().decode(firebaseCredentials);
            return GoogleCredentials.fromStream(new ByteArrayInputStream(decodedCredentials));
        }

        // Priority 2: JSON file in classpath (for localhost development)
        if (firebaseCredentialsFile != null && !firebaseCredentialsFile.isBlank()) {
            log.info("Loading Firebase credentials from classpath: {}", firebaseCredentialsFile);
            ClassPathResource resource = new ClassPathResource(firebaseCredentialsFile);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return GoogleCredentials.fromStream(is);
                }
            } else {
                log.warn("Firebase credentials file not found: {}", firebaseCredentialsFile);
            }
        }

        return null;
    }

    @Bean
    public Firestore firestore() {
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("Firebase not initialized. Returning null Firestore instance.");
            return null;
        }
        return FirestoreClient.getFirestore();
    }
}
