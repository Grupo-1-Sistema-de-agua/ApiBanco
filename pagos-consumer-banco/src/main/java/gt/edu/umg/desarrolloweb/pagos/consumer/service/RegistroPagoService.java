package gt.edu.umg.desarrolloweb.pagos.consumer.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.Select;

/**
 * Habla con las dos tablas de DynamoDB:
 *  - pagos_enviados: el flag "ya se entrego al banco"
 *  - pagos_log:      un registro por cada intento
 */
@Service
public class RegistroPagoService {

    private static final Logger log = LoggerFactory.getLogger(RegistroPagoService.class);

    private final DynamoDbClient dynamo;
    private final String tablaFlag;
    private final String tablaLog;

    public RegistroPagoService(DynamoDbClient dynamo,
                               @Value("${aws.dynamodb.tabla-flag}") String tablaFlag,
                               @Value("${aws.dynamodb.tabla-log}") String tablaLog) {
        this.dynamo = dynamo;
        this.tablaFlag = tablaFlag;
        this.tablaLog = tablaLog;
    }

    /** true si este pago ya fue entregado al banco en un intento anterior. */
    public boolean yaEnviado(String idempotencyKey) {
        try {
            var respuesta = dynamo.getItem(GetItemRequest.builder()
                    .tableName(tablaFlag)
                    .key(Map.of("idempotencyKey", texto(idempotencyKey)))
                    .build());
            return respuesta.hasItem();
        } catch (Exception e) {
            log.error("No se pudo consultar el flag en DynamoDB: {}", e.getMessage());
            return false;
        }
    }

    /** Guarda el flag: este pago ya fue entregado al banco. */
    public void marcarEnviado(String idempotencyKey, Long paymentId, String receiptId) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("idempotencyKey", texto(idempotencyKey));
            item.put("paymentId", numero(paymentId));
            item.put("enviado", AttributeValue.builder().bool(true).build());
            item.put("receiptId", texto(receiptId));
            item.put("fecha", texto(Instant.now().toString()));

            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tablaFlag)
                    .item(item)
                    .build());

            log.info("Flag guardado en DynamoDB: {} -> enviado", idempotencyKey);

        } catch (Exception e) {
            log.error("No se pudo guardar el flag en DynamoDB: {}", e.getMessage());
        }
    }

    /** Anota un intento (EXITOSO o FALLIDO) en la tabla de logs. */
    public void registrarIntento(String idempotencyKey, Long paymentId, String estado,
                                 int httpStatus, String codigoError,
                                 String correlationId, String receiptId) {
        try {
            String momento = Instant.now().toString();
            int intento = siguienteIntento(idempotencyKey);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("idempotencyKey", texto(idempotencyKey));
            item.put("momento", texto(momento));
            item.put("paymentId", numero(paymentId));
            item.put("destino", texto("BANCO"));
            item.put("estado", texto(estado));
            item.put("intento", numero((long) intento));
            item.put("httpStatus", numero((long) httpStatus));
            item.put("codigoError", texto(codigoError));
            item.put("correlationId", texto(correlationId));
            item.put("receiptId", texto(receiptId));
            item.put("fecha", texto(momento));

            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tablaLog)
                    .item(item)
                    .build());

            log.info("Log guardado en DynamoDB: {} intento={} estado={}", idempotencyKey, intento, estado);

        } catch (Exception e) {
            log.error("No se pudo guardar el log en DynamoDB: {}", e.getMessage());
        }
    }

    /** Cuenta cuantos intentos hay de este pago y devuelve el siguiente numero. */
    private int siguienteIntento(String idempotencyKey) {
        try {
            var respuesta = dynamo.query(QueryRequest.builder()
                    .tableName(tablaLog)
                    .select(Select.COUNT)
                    .keyConditionExpression("idempotencyKey = :k")
                    .expressionAttributeValues(Map.of(":k", texto(idempotencyKey)))
                    .build());
            return respuesta.count() + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private AttributeValue texto(String valor) {
        return AttributeValue.builder()
                .s(valor == null || valor.isBlank() ? "-" : valor)
                .build();
    }

    private AttributeValue numero(Long valor) {
        return AttributeValue.builder()
                .n(valor == null ? "0" : String.valueOf(valor))
                .build();
    }
}