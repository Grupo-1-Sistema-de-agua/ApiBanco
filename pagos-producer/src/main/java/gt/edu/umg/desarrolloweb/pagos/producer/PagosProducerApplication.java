package gt.edu.umg.desarrolloweb.pagos.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PagosProducerApplication {

	public static void main(String[] args) {
		SpringApplication.run(PagosProducerApplication.class, args);
	}

}
