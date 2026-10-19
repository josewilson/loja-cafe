package br.com.lojacafe.pedidos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// O teste não precisa do Kafka no ar nem do banco em arquivo: sobe sem criar tópicos,
// sem ligar o listener e com H2 em memória (não mexe em ./dados).
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:teste"
})
class PedidosApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
