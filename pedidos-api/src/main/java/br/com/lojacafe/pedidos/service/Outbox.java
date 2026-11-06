package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.EventoSaida;
import br.com.lojacafe.pedidos.repository.EventoSaidaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Padrão Outbox: quem muda o banco só ANOTA o evento (na mesma transação); este relé o entrega ao Kafka logo depois.
 * Entrega "pelo menos uma vez": se o envio der certo mas o registro falhar, o evento sai de novo, e os consumidores
 * já ignoram repetição (specs/eventos.md). Se o Kafka cair, os eventos esperam no banco e saem quando ele voltar, na ordem.
 */
@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final EventoSaidaRepository eventos;
    private final KafkaTemplate<String, String> kafka;

    public Outbox(EventoSaidaRepository eventos, KafkaTemplate<String, String> kafka) {
        this.eventos = eventos;
        this.kafka = kafka;
    }

    /** Roda na transação de quem chama: se ela desfaz, o evento some junto. */
    public void enfileirar(String topico, String chave, String payload) {
        eventos.save(new EventoSaida(topico, chave, payload));
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    @Transactional
    public void enviarPendentes() {
        for (EventoSaida e : eventos.pendentes()) {
            try {
                // Chave = pedidoId: todos os eventos do mesmo pedido caem na mesma partição.
                kafka.send(e.getTopico(), e.getChave(), e.getPayload()).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Kafka não recebeu o evento {} ({}): tento de novo daqui a pouco", e.getId(), ex.toString());
                return; // para aqui: manter a ordem importa mais que adiantar os de trás
            }
            e.marcarEnviado();
        }
    }

    /** Os já enviados ficam 7 dias (para conferir) e depois saem. */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 120_000)
    @Transactional
    public void limpar() {
        eventos.apagarEnviadosAntesDe(Instant.now().minus(Duration.ofDays(7)));
    }
}
