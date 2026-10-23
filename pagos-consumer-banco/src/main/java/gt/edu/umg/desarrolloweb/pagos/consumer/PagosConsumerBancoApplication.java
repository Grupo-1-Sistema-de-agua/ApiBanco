package gt.edu.umg.desarrolloweb.pagos.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients   // activa el cliente Feign generado desde el YAML del banco
public class PagosConsumerBancoApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagosConsumerBancoApplication.class, args);
    }
}