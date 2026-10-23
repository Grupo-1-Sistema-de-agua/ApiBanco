package gt.edu.umg.desarrolloweb.pagos.consumer.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Crea la conexion con DynamoDB Local (una sola vez para todo el programa).
 */
@Configuration
public class DynamoConfig {

    @Bean
    DynamoDbClient dynamoDbClient(
            @Value("${aws.dynamodb.endpoint}") String endpoint,
            @Value("${aws.dynamodb.region}") String region,
            @Value("${aws.dynamodb.access-key}") String accessKey,
            @Value("${aws.dynamodb.secret-key}") String secretKey) {

        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}