package gt.edu.umg.desarrolloweb.pagos.producer.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara el exchange, la cola y la DLQ.
 * Son los MISMOS nombres y argumentos que usa el consumidor, para que
 * el productor pueda publicar aunque el consumidor aun no haya arrancado.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "pagos.exchange";
    public static final String QUEUE_BANCO = "pagos.banco";
    public static final String ROUTING_KEY_BANCO = "banco";

    public static final String DLX = "pagos.dlx";
    public static final String QUEUE_BANCO_DLQ = "pagos.banco.dlq";
    public static final String ROUTING_KEY_BANCO_DLQ = "banco.dlq";

    @Bean
    DirectExchange pagosExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue colaBanco() {
        return QueueBuilder.durable(QUEUE_BANCO)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_BANCO_DLQ)
                .build();
    }

    @Bean
    Binding bindingBanco(Queue colaBanco, DirectExchange pagosExchange) {
        return BindingBuilder.bind(colaBanco).to(pagosExchange).with(ROUTING_KEY_BANCO);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue colaBancoDlq() {
        return QueueBuilder.durable(QUEUE_BANCO_DLQ).build();
    }

    @Bean
    Binding bindingBancoDlq(Queue colaBancoDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(colaBancoDlq).to(deadLetterExchange).with(ROUTING_KEY_BANCO_DLQ);
    }
}
