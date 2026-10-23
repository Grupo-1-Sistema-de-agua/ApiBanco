package gt.edu.umg.desarrolloweb.pagos.consumer.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Mensaje que el productor deja en la cola pagos.banco.
 * Es el pago "en nuestro formato", todavia NO en el formato del banco.
 */
public record PagoMensaje(
        Long paymentId,
        String idempotencyKey,     // ej. EQA-000123 (estable en todos los reintentos)
        String customerCode,       // ej. CLI-0001
        String fullName,
        String taxId,              // NIT o CF
        BigDecimal amount,         // en quetzales, ej. 150.75
        OffsetDateTime transactionDate,   // ISO-8601 con zona, ej. 2026-10-01T16:15:00Z
        String channel,            // CASH, CARD, BANK_TRANSFER, CHECK
        String description) {
}