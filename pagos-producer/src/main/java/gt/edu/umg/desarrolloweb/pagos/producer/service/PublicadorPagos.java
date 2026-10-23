package gt.edu.umg.desarrolloweb.pagos.producer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.edu.umg.desarrolloweb.pagos.producer.config.RabbitConfig;
import gt.edu.umg.desarrolloweb.pagos.producer.repository.PagoRepository;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada 30 segundos:
 *   1. lee los pagos pendientes de MariaDB,
 *   2. omite los que ya tienen flag en DynamoDB,
 *   3. publica el resto en pagos.exchange con routing key "banco".
 */
@Component
public class PublicadorPagos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorPagos.class);

    /**
     * Evita republicar el mismo pago en cada ciclo mientras el consumidor
     * todavia no ha escrito el flag (por ejemplo, si el banco esta lento).
     */
    private final Set<String> publicadosEnEstaCorrida = ConcurrentHashMap.newKeySet();

    private final PagoRepository pagoRepository;
    private final PagoMapper pagoMapper;
    private final FlagEnviadosService flagEnviados;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final int limite;

    public PublicadorPagos(PagoRepository pagoRepository,
                           PagoMapper pagoMapper,
                           FlagEnviadosService flagEnviados,
                           RabbitTemplate rabbitTemplate,
                           ObjectMapper objectMapper,
                           @Value("${producer.limite-por-ciclo:20}") int limite) {
        this.pagoRepository = pagoRepository;
        this.pagoMapper = pagoMapper;
        this.flagEnviados = flagEnviados;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.limite = limite;
    }

    @Scheduled(fixedDelayString = "${producer.intervalo-ms:30000}",
               initialDelayString = "${producer.retraso-inicial-ms:5000}")
    public void publicarPendientes() {
        // Se leen mas filas de las que se van a publicar, porque las primeras
        // pueden estar ya enviadas (tienen flag) y hay que llegar a las siguientes.
        int filasARevisar = Math.max(limite * 10, 100);
        var pendientes = pagoRepository.buscarPendientes(filasARevisar);
        int publicados = 0;

        for (var fila : pendientes) {
            if (publicados >= limite) {
                break;
            }

            var mensaje = pagoMapper.aMensaje(fila);
            String key = mensaje.idempotencyKey();

            if (publicadosEnEstaCorrida.contains(key)) {
                continue;
            }

            if (flagEnviados.yaEnviado(key)) {
                publicadosEnEstaCorrida.add(key);
                log.debug("Ya enviado al banco, se omite: {}", key);
                continue;
            }

            try {
                String json = objectMapper.writeValueAsString(mensaje);
                String correlationId = UUID.randomUUID().toString();

                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY_BANCO, json,
                        m -> {
                            m.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
                            m.getMessageProperties().setHeader("X-Correlation-Id", correlationId);
                            return m;
                        });

                publicadosEnEstaCorrida.add(key);
                publicados++;
                log.info("Publicado en RabbitMQ: key={} paymentId={} monto={} canal={}",
                        key, mensaje.paymentId(), mensaje.amount(), mensaje.channel());

            } catch (JsonProcessingException e) {
                log.error("No se pudo convertir el pago {} a JSON: {}", key, e.getMessage());
            } catch (Exception e) {
                log.error("No se pudo publicar el pago {}: {}", key, e.getMessage());
            }
        }

        if (publicados > 0) {
            log.info("Ciclo terminado: {} pago(s) publicado(s)", publicados);
        }
    }
}
