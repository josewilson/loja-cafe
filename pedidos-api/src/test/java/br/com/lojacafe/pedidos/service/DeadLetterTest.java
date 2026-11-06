package br.com.lojacafe.pedidos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.core.exc.StreamReadException;

/** Mensagem que não dá para processar vai para pagamentos-resultados.DLT, sem travar o consumo. */
class DeadLetterTest {

    @SuppressWarnings("unchecked")
    @Test
    void jsonIlegivelVaiDiretoParaADlt() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var tratador = PagamentosKafka.erroDoConsumidor(kafka);
        var registro = new ConsumerRecord<>(PagamentosKafka.PAGAMENTOS_RESULTADOS, 2, 7L, "12", "{isto nao e json");

        boolean seguiu = tratador.handleOne(new RuntimeException(new StreamReadException(null, "json ruim")),
                registro, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertTrue(seguiu, "a mensagem foi dada como tratada: o consumo anda");
        var enviado = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(enviado.capture());
        assertEquals("pagamentos-resultados.DLT", enviado.getValue().topic());
        assertEquals("{isto nao e json", enviado.getValue().value());
    }
}
