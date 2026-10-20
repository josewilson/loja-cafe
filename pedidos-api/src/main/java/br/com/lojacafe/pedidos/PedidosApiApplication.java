package br.com.lojacafe.pedidos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@EnableScheduling
public class PedidosApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PedidosApiApplication.class, args);
	}

	/** Um só BCrypt para a API toda. O custo (padrão 10) é configurável: os testes usam 4 para rodar rápido. */
	@Bean
	BCryptPasswordEncoder bcrypt(@Value("${lojacafe.bcrypt-custo:10}") int custo) {
		return new BCryptPasswordEncoder(custo);
	}
}
