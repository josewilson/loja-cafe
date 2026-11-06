package br.com.lojacafe.pedidos.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.lojacafe.pedidos.model.EventoSaida;
import br.com.lojacafe.pedidos.repository.EventoSaidaRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/** A Outbox entrega em ordem, marca o que saiu e, se o Kafka falhar, para e deixa o resto para a próxima rodada. */
class OutboxTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final EventoSaidaRepository repo = mock(EventoSaidaRepository.class);
    private final Outbox outbox = new Outbox(repo, kafka);

    @Test
    void marcaComoEnviadoOQueSaiu() {
        var e = new EventoSaida("pedidos-criados", "1", "{}");
        when(repo.pendentes()).thenReturn(List.of(e));
        when(kafka.send("pedidos-criados", "1", "{}")).thenReturn(CompletableFuture.completedFuture(null));
        outbox.enviarPendentes();
        assertNotNull(e.getEnviadoEm());
    }

    @Test
    void kafkaForaDoArNaoMarcaENaoPassaParaOProximo() {
        var primeiro = new EventoSaida("pedidos-criados", "1", "{}");
        var segundo = new EventoSaida("pedidos-criados", "2", "{}");
        when(repo.pendentes()).thenReturn(List.of(primeiro, segundo));
        when(kafka.send(eq("pedidos-criados"), eq("1"), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker fora")));
        outbox.enviarPendentes();
        assertNull(primeiro.getEnviadoEm());
        assertNull(segundo.getEnviadoEm());
        verify(kafka, never()).send(eq("pedidos-criados"), eq("2"), any());
    }

    @Test
    void enfileirarGravaNaTransacaoDeQuemChama() {
        outbox.enfileirar("pagamentos-estornos", "7", "{}");
        verify(repo).save(any(EventoSaida.class));
        verify(kafka, never()).send(any(String.class), any(), any()); // nada vai ao broker aqui
    }
}
