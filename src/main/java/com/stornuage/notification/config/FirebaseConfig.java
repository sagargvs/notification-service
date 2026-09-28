package com.stornuage.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Firebase Admin SDK initialization.
 *
 * In prod: pulls service account JSON from AWS Secrets Manager.
 * In dev: falls back to a local file path if configured.
 *
 * Never put the service account JSON in env vars or Docker image layers.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FirebaseConfig {

    private final NotificationProperties properties;

    @Bean
    public FirebaseApp firebaseApp(SecretsManagerClient secretsManagerClient) throws Exception {
        var fbProps = properties.getFirebase();
        InputStream credentialsStream;

        if (fbProps.getCredentialsJson() != null && !fbProps.getCredentialsJson().isBlank()) {
            log.info("Loading Firebase credentials from FCM_CREDENTIALS_JSON env var (Fargate)");
            credentialsStream = new ByteArrayInputStream(
                    fbProps.getCredentialsJson().getBytes(StandardCharsets.UTF_8));
        } else if (fbProps.getCredentialsPath() != null && !fbProps.getCredentialsPath().isBlank()) {
            log.info("Loading Firebase credentials from local path (dev mode)");
            credentialsStream = new FileInputStream(fbProps.getCredentialsPath());
        } else {
            log.info("Loading Firebase credentials from Secrets Manager: {}",
                    fbProps.getCredentialsSecretName());
            var response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(fbProps.getCredentialsSecretName())
                            .build());
            credentialsStream = new ByteArrayInputStream(
                    response.secretString().getBytes(StandardCharsets.UTF_8));
        }

        var options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(credentialsStream))
                .build();

        // Check if FirebaseApp is already initialized (Spring hot-reload, etc.)
        if (FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.initializeApp(options);
        }
        return FirebaseApp.getInstance();
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }
}
