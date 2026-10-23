package gt.edu.umg.desarrolloweb.pagos.producer.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Pregunta a DynamoDB si un pago ya fue enviado al banco.
 * El flag lo escribe el consumidor SOLO cuando el banco responde 201
 * o 409 DUPLICATE_PAYMENT.
 */
@Service
public class FlagEnviadosService {

    private final DynamoDbClient dynamo;
    private final String tablaFlag;

    public FlagEnviadosService(DynamoDbClient dynamo,
                               @Value("${aws.dynamodb.tabla-flag}") String tablaFlag) {
        this.dynamo = dynamo;
        this.tablaFlag = tablaFlag;
    }

    public boolean yaEnviado(String idempotencyKey) {
        try {
            var respuesta = dynamo.getItem(GetItemRequest.builder()
                    .tableName(tablaFlag)
                    .consistentRead(true)
                    .key(Map.of("idempotencyKey", AttributeValue.fromS(idempotencyKey)))
                    .build());
            return respuesta.hasItem();
        } catch (ResourceNotFoundException e) {
            // La tabla aun no existe (el consumidor no ha arrancado nunca).
            return false;
        }
    }
}
