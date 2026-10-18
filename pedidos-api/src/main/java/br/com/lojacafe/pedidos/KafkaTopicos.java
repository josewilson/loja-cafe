package br.com.lojacafe.pedidos;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Cria os dois tópicos do contrato (se ainda não existirem) com 3 partições. */
@Configuration
public class KafkaTopicos {

    public static final String PEDIDOS_CRIADOS = "pedidos-criados";
    public static final String PAGAMENTOS_RESULTADOS = "pagamentos-resultados";

    @Bean
    NewTopic pedidosCriados() {
        return TopicBuilder.name(PEDIDOS_CRIADOS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic pagamentosResultados() {
        return TopicBuilder.name(PAGAMENTOS_RESULTADOS).partitions(3).replicas(1).build();
    }
}
