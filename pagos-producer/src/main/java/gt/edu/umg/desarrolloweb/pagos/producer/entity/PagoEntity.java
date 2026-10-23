package gt.edu.umg.desarrolloweb.pagos.producer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Vista de SOLO LECTURA de la tabla Tb_Pagos del monolito.
 * Solo mapeamos las columnas que necesitamos.
 */
@Entity
@Table(name = "Tb_Pagos")
public class PagoEntity {

    @Id
    private Integer id;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal monto;

    @Column(name = "fecha_pago", nullable = false)
    private LocalDateTime fechaPago;

    protected PagoEntity() {
    }

    public Integer getId() {
        return id;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public LocalDateTime getFechaPago() {
        return fechaPago;
    }
}