package gt.edu.umg.desarrolloweb.pagos.producer.service;

import gt.edu.umg.desarrolloweb.pagos.producer.model.PagoMensaje;
import gt.edu.umg.desarrolloweb.pagos.producer.repository.PagoPendienteProjection;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Traduce una fila de Tb_Pagos al mensaje que viaja por RabbitMQ.
 * No llama al banco: solo arma el JSON.
 */
@Component
public class PagoMapper {

    /** Prefijo del equipo. Debe coincidir con TEAM_CODE (EQUIPO_A). */
    public static final String PREFIJO_IDEMPOTENCIA = "EQA";

    private static final ZoneId ZONA_GUATEMALA = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter PERIODO = DateTimeFormatter.ofPattern("yyyy-MM");

    public PagoMensaje aMensaje(PagoPendienteProjection pago) {
        String taxId = (pago.getClienteDpi() == null || pago.getClienteDpi().isBlank())
                ? "CF"
                : pago.getClienteDpi();

        return new PagoMensaje(
                pago.getId().longValue(),
                String.format("%s-%06d", PREFIJO_IDEMPOTENCIA, pago.getId()),
                String.format("CLI-%04d", pago.getClienteId()),
                pago.getClienteNombre(),
                taxId,
                pago.getMonto(),
                pago.getFechaPago().atZone(ZONA_GUATEMALA).toOffsetDateTime(),
                canal(pago.getMetodoNombre()),
                "Servicio de agua " + PERIODO.format(pago.getFechaPago()));
    }

    /** Efectivo -> CASH; Transferencia y Deposito -> BANK_TRANSFER. */
    private String canal(String metodo) {
        String normalizado = (metodo == null) ? "" : metodo.trim().toLowerCase(Locale.ROOT);
        return switch (normalizado) {
            case "efectivo" -> "CASH";
            case "transferencia", "deposito", "depósito" -> "BANK_TRANSFER";
            default -> throw new IllegalArgumentException("Metodo de pago no soportado: " + metodo);
        };
    }
}
