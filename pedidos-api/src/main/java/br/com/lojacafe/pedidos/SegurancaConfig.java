package br.com.lojacafe.pedidos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Um só BCrypt para a API toda. O custo (padrão 10) é configurável: os testes usam 4 para rodar rápido. */
@Configuration
public class SegurancaConfig {

    @Bean
    BCryptPasswordEncoder bcrypt(@Value("${lojacafe.bcrypt-custo:10}") int custo) {
        return new BCryptPasswordEncoder(custo);
    }
}
