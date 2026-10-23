package gt.edu.umg.desarrolloweb.pagos.consumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Crea las dos tablas de DynamoDB al arrancar (si no existen).
 */
@Component
public class DynamoTablasConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoTablasConfig.class);

    private final DynamoDbClient dynamo;
    private final String tablaFlag;
    private final String tablaLog;

    public DynamoTablasConfig(DynamoDbClient dynamo,
                              @Value("${aws.dynamodb.tabla-flag}") String tablaFlag,
                              @Value("${aws.dynamodb.tabla-log}") String tablaLog) {
        this.dynamo = dynamo;
        this.tablaFlag = tablaFlag;
        this.tablaLog = tablaLog;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void crearTablas() {
        crearTablaFlag();
        crearTablaLog();
    }

    /** pagos_enviados: llave simple (idempotencyKey). */
    private void crearTablaFlag() {
        try {
            dynamo.createTable(CreateTableRequest.builder()
                    .tableName(tablaFlag)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("idempotencyKey")
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("idempotencyKey")
                            .keyType(KeyType.HASH)
                            .build())
                    .build());
            log.info("Tabla DynamoDB creada: {}", tablaFlag);
        } catch (ResourceInUseException e) {
            log.info("Tabla DynamoDB ya existia: {}", tablaFlag);
        }
    }

    /** pagos_log: llave compuesta (idempotencyKey + momento). */
    private void crearTablaLog() {
        try {
            dynamo.createTable(CreateTableRequest.builder()
                    .tableName(tablaLog)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("idempotencyKey")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("momento")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("idempotencyKey")
                                    .keyType(KeyType.HASH)
                                    .build(),
                            KeySchemaElement.builder()
                                    .attributeName("momento")
                                    .keyType(KeyType.RANGE)
                                    .build())
                    .build());
            log.info("Tabla DynamoDB creada: {}", tablaLog);
        } catch (ResourceInUseException e) {
            log.info("Tabla DynamoDB ya existia: {}", tablaLog);
        }
    }
}