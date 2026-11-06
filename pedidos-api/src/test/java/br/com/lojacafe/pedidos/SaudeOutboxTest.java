package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.lojacafe.pedidos.controller.SaudeController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/** O /saude só acusa a Outbox quando há evento esperando há muito tempo (a consulta conta os pendentes antigos). */
class SaudeOutboxTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SaudeController saude = new SaudeController(jdbc, mock(KafkaAdmin.class));

    private void pendentesAntigos(Long quantidade) {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Long>>any(), any(Object[].class))).thenReturn(quantidade);
    }

    @Test
    void semPendenteAntigoEstaEmDia() {
        pendentesAntigos(0L);
        assertTrue(saude.outboxEmDia());
    }

    @Test
    void comPendenteAntigoEstaAtrasada() {
        pendentesAntigos(3L);
        assertFalse(saude.outboxEmDia());
    }

    @Test
    void semBancoNaoDuplicaOAlarme() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<Class<Long>>any(), any(Object[].class)))
                .thenThrow(new RuntimeException("banco fora"));
        assertTrue(saude.outboxEmDia()); // o "banco: falha" já derruba o status
    }
}
