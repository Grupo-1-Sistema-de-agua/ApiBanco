package gt.edu.umg.desarrolloweb.pagos.consumer.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import gt.edu.umg.desarrolloweb.pagos.consumer.bank.dto.BankPaymentReceiptDto;
import gt.edu.umg.desarrolloweb.pagos.consumer.config.RabbitConfig;
import gt.edu.umg.desarrolloweb.pagos.consumer.exception.BancoReintentableException;
import gt.edu.umg.desarrolloweb.pagos.consumer.model.PagoMensaje;
import gt.edu.umg.desarrolloweb.pagos.consumer.service.BancoService;
import gt.edu.umg.desarrolloweb.pagos.consumer.service.RegistroPagoService;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * PASO D + E: recibe un pago, lo envia al banco y anota el resultado en DynamoDB.
 *
 *   - ya enviado antes       -> se ignora (idempotencia local)
 *   - 201                    -> flag + log EXITOSO
 *   - 409 DUPLICATE_PAYMENT  -> flag + log EXITOSO
 *   - 429/500/502/503/504/-1 -> log FALLIDO y se reintenta
 *   - cualquier otro         -> log FALLIDO y va directo a la DLQ
 */
@Component
public class PagoBancoListener {

    private static final Logger log = LoggerFactory.getLogger(PagoBancoListener.class);

    /** Codigos HTTP del banco que se consideran temporales. */
    private static final Set<Integer> ESTADOS_REINTENTABLES = Set.of(429, 500, 502, 503, 504);

    private final BancoService bancoService;
    private final RegistroPagoService registro;
    private final ObjectMapper objectMapper;

    public PagoBancoListener(BancoService bancoService,
                             RegistroPagoService registro,
                             ObjectMapper objectMapper) {
        this.bancoService = bancoService;
        this.registro = registro;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_BANCO)
    public void recibir(PagoMensaje pago) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[{}] Pago recibido: paymentId={} idempotencyKey={}",
                correlationId, pago.paymentId(), pago.idempotencyKey());

        // 1) Idempotencia local: si ya se envio, no se repite
        if (registro.yaEnviado(pago.idempotencyKey())) {
            log.info("[{}] El pago {} ya estaba enviado al banco. Se ignora.",
                    correlationId, pago.idempotencyKey());
            return;
        }

        try {
            BankPaymentReceiptDto comprobante = bancoService.enviar(pago, correlationId);

            registro.marcarEnviado(pago.idempotencyKey(), pago.paymentId(),
                    comprobante.getReceiptId());
            registro.registrarIntento(pago.idempotencyKey(), pago.paymentId(),
                    "EXITOSO", 201, "", correlationId, comprobante.getReceiptId());

            log.info("[{}] ENTREGADO al banco. receiptId={}",
                    correlationId, comprobante.getReceiptId());

        } catch (FeignException e) {
            int status = e.status();
            String codigo = extraerCodigo(e);

            // 2) El banco ya tenia este pago: se da por entregado
            if (status == 409 && "DUPLICATE_PAYMENT".equals(codigo)) {
                registro.marcarEnviado(pago.idempotencyKey(), pago.paymentId(), "");
                registro.registrarIntento(pago.idempotencyKey(), pago.paymentId(),
                        "EXITOSO", status, codigo, correlationId, "");

                log.info("[{}] El banco ya tenia este pago (DUPLICATE_PAYMENT). Se da por entregado.",
                        correlationId);
                return;
            }

            // 3) Cualquier otro error: se anota como FALLIDO
            registro.registrarIntento(pago.idempotencyKey(), pago.paymentId(),
                    "FALLIDO", status, codigo, correlationId, "");

            if (esReintentable(status)) {
                log.warn("[{}] Fallo temporal del banco. status={} code={} -> se reintentara",
                        correlationId, status, codigo);
                throw new BancoReintentableException(
                        "Fallo temporal del banco: " + status + " " + codigo, e);
            }

            log.error("[{}] Rechazo del banco. status={} code={} -> cola de errores",
                    correlationId, status, codigo);
            throw new AmqpRejectAndDontRequeueException(
                    "Banco respondio " + status + " " + codigo, e);

        } catch (IllegalArgumentException | ArithmeticException | NullPointerException e) {
            registro.registrarIntento(pago.idempotencyKey(), pago.paymentId(),
                    "FALLIDO", 0, "MENSAJE_INVALIDO", correlationId, "");

            log.error("[{}] Mensaje invalido, no se puede convertir al formato del banco: {}",
                    correlationId, e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Mensaje invalido: " + e.getMessage(), e);
        }
    }

    private boolean esReintentable(int status) {
        return status == -1 || ESTADOS_REINTENTABLES.contains(status);
    }

    /** Lee el campo "code" del JSON de error. Si no hay (API Gateway), devuelve "". */
    private String extraerCodigo(FeignException e) {
        try {
            String cuerpo = e.contentUTF8();
            if (cuerpo == null || cuerpo.isBlank()) {
                return "";
            }
            return objectMapper.readTree(cuerpo).path("code").asText("");
        } catch (Exception ignorado) {
            return "";
        }
    }
}