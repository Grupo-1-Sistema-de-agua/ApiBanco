package gt.edu.umg.desarrolloweb.pagos.producer.repository;

import gt.edu.umg.desarrolloweb.pagos.producer.entity.PagoEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PagoRepository extends JpaRepository<PagoEntity, Integer> {

    @Query(value = """
            SELECT p.id            AS id,
                   p.monto         AS monto,
                   p.fecha_pago    AS fecha_pago,
                   c.id            AS cliente_id,
                   c.nombre        AS cliente_nombre,
                   c.dpi           AS cliente_dpi,
                   m.nombre        AS metodo_nombre,
                   l.numero_recibo AS numero_recibo
              FROM Tb_Pagos p
              JOIN Tb_Lecturas l     ON l.id = p.lectura_id
              JOIN Tb_Contadores ct  ON ct.id = l.contador_id
              JOIN Tb_Clientes c     ON c.id = ct.cliente_id
              JOIN Tb_Metodos_Pago m ON m.id = p.metodo_id
             WHERE p.anulado = 0
             ORDER BY p.id
             LIMIT :limite
            """, nativeQuery = true)
    List<PagoPendienteProjection> buscarPendientes(@Param("limite") int limite);
}