package gt.edu.umg.desarrolloweb.pagos.consumer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Le dice a Spring que los mensajes de RabbitMQ son JSON,
 * para que el listener reciba un objeto PagoMensaje y no un texto suelto.
 */
@Configuration
public class MensajeriaJsonConfig {

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}

