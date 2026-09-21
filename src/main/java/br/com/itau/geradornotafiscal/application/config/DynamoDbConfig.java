package br.com.itau.geradornotafiscal.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Fora do perfil local, {@code app.outbox.dynamodb.endpoint-override} fica vazio e o client usa
 * resolução padrão de endpoint/credenciais da AWS (ex.: task role do ECS) — nenhum código muda
 * entre ambientes, só a configuração externa (P4, REQ-5.6).
 */
@Configuration
public class DynamoDbConfig {

    @Bean
    DynamoDbClient dynamoDbClient(@Value("${app.outbox.dynamodb.endpoint-override:}") String endpointOverride,
                                   @Value("${app.outbox.dynamodb.region:us-east-1}") String region) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }
}
