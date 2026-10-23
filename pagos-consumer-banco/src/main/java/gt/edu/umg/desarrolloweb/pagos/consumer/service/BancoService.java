package gt.edu.umg.desarrolloweb.pagos.consumer.service;

import gt.edu.umg.desarrolloweb.pagos.consumer.bank.client.BankPaymentsApiClient;
import gt.edu.umg.desarrolloweb.pagos.consumer.bank.dto.BankPaymentReceiptDto;
import gt.edu.umg.desarrolloweb.pagos.consumer.bank.dto.BankPaymentRequestDto;
import gt.edu.umg.desarrolloweb.pagos.consumer.bank.dto.MoneyDto;
import gt.edu.umg.desarrolloweb.pagos.consumer.bank.dto.PayerDto;
import gt.edu.umg.desarrolloweb.pagos.consumer.bank.dto.TeamCodeDto;
import gt.edu.umg.desarrolloweb.pagos.consumer.model.PagoMensaje;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Traduce nuestro pago al formato del banco y llama al API del banco.
 * Si el banco responde con error, el cliente Feign lanza FeignException
 * (la atrapa el listener).
 */
@Service
public class BancoService {

    private final BankPaymentsApiClient bankApi;
    private final String teamCode;
    private final String simulationScenario;

    public BancoService(BankPaymentsApiClient bankApi,
                        @Value("${bank.team-code:EQUIPO_A}") String teamCode,
                        @Value("${bank.simulation-scenario:}") String simulationScenario) {
        this.bankApi = bankApi;
        this.teamCode = teamCode;
        this.simulationScenario = simulationScenario;
    }

    public BankPaymentReceiptDto enviar(PagoMensaje pago, String correlationId) {
        BankPaymentRequestDto request = mapear(pago);

        // Header de pruebas: solo se manda si lo configuraste (variable BANK_SCENARIO)
        String escenario = (simulationScenario == null || simulationScenario.isBlank())
                ? null : simulationScenario;

        ResponseEntity<BankPaymentReceiptDto> respuesta = bankApi.sendBankPayment(
                pago.idempotencyKey(),   // header Idempotency-Key
                request,                 // body JSON
                correlationId,           // header X-Correlation-Id
                escenario);              // header X-Simulation-Scenario (opcional)

        return respuesta.getBody();
    }

    private BankPaymentRequestDto mapear(PagoMensaje pago) {
        // Q150.75 -> 15075 centavos. Nunca se usa double.
        long centavos = pago.amount()
                .setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact();

        PayerDto pagador = new PayerDto()
                .customerCode(pago.customerCode())
                .fullName(pago.fullName())
                .taxId(pago.taxId());

        MoneyDto monto = new MoneyDto()
                .valueInCents(centavos)
                .currency(MoneyDto.CurrencyEnum.GTQ);

        return new BankPaymentRequestDto()
                .teamCode(TeamCodeDto.fromValue(teamCode))
                .payer(pagador)
                .amount(monto)
                .transactionDate(pago.transactionDate())
                .channel(BankPaymentRequestDto.ChannelEnum.fromValue(pago.channel()))
                .description(pago.description());
    }
}