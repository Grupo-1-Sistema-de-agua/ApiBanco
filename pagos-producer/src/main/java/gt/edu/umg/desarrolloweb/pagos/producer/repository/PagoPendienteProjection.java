package gt.edu.umg.desarrolloweb.pagos.producer.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Columnas que devuelve la consulta de pagos pendientes. */
public interface PagoPendienteProjection {

    Long getId();

    BigDecimal getMonto();

    LocalDateTime getFechaPago();

    Long getClienteId();

    String getClienteNombre();

    String getClienteDpi();

    String getMetodoNombre();

    String getNumeroRecibo();
}