package gt.edu.umg.desarrolloweb.pagos.consumer.exception;

/**
 * Etiqueta para errores TEMPORALES del banco.
 * El consumidor reintenta estos con la misma Idempotency-Key.
 * Casos: 429, 500, 502, 503, 504 y timeout.
 */
public class BancoReintentableException extends RuntimeException {

    public BancoReintentableException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}