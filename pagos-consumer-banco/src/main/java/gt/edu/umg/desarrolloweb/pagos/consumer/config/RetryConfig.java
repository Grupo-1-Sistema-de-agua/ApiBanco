package gt.edu.umg.desarrolloweb.pagos.consumer.config;

import gt.edu.umg.desarrolloweb.pagos.consumer.exception.BancoReintentableException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Spring Boot 3.5 ya no tiene la propiedad "retryable-exceptions".
 * Aqui se define a mano la politica: SOLO BancoReintentableException se
 * reintenta. Un 400/422 (error de datos) va directo a la cola de errores.
 */
@Configuration
public class RetryConfig {

    @Bean
    RabbitRetryTemplateCustomizer reintentarSoloErroresDelBanco(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:5}") int maxIntentos) {

        return (target, retryTemplate) -> retryTemplate.setRetryPolicy(
                new SimpleRetryPolicy(
                        maxIntentos,
                        Map.of(BancoReintentableException.class, true),
                        true));
    }
}
