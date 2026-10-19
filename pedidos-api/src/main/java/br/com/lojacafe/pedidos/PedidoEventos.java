package br.com.lojacafe.pedidos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Ato 3: anota no tópico pedidos-criados que um pedido nasceu (specs/eventos.md). */
@Component
public class PedidoEventos {

    record ItemEvento(String item, int quantidade) {}

    record PedidoCriado(int versao, Long pedidoId, Long lojaId, String cliente, List<ItemEvento> itens, BigDecimal valor,
                        FormaPagamento formaPagamento, Integer parcelas, Instant criadoEm) {}

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public PedidoEventos(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publicarCriado(Pedido p) throws Exception {
        var itens = p.getItens().stream().map(i -> new ItemEvento(i.getItem(), i.getQuantidade())).toList();
        var evento = new PedidoCriado(3, p.getId(), p.getLojaId(), p.getCliente(), itens, p.getValor(),
                p.getFormaPagamento(), p.getParcelas(), p.getCriadoEm());
        // Chave = pedidoId: todos os eventos do mesmo pedido caem na mesma partição.
        // .get() espera o "ok" do broker; se o Kafka estiver fora, a falha aparece aqui.
        kafka.send(KafkaTopicos.PEDIDOS_CRIADOS, String.valueOf(p.getId()), json.writeValueAsString(evento))
                .get(5, TimeUnit.SECONDS);
    }
}
