package br.com.lojacafe.pedidos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Ato 3: lê pagamentos-resultados e atualiza o status do pedido. */
@Component
public class ResultadoPagamentoListener {

    record ResultadoPagamento(int versao, Long pedidoId, String status, String motivo,
                              String instancia, String processadoEm) {}

    private static final Logger log = LoggerFactory.getLogger(ResultadoPagamentoListener.class);

    private final PedidoRepository repository;
    private final JsonMapper json;

    public ResultadoPagamentoListener(PedidoRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @KafkaListener(topics = KafkaTopicos.PAGAMENTOS_RESULTADOS)
    public void receber(String mensagem) {
        var resultado = json.readValue(mensagem, ResultadoPagamento.class);
        repository.findById(resultado.pedidoId()).ifPresentOrElse(pedido -> {
            // concluir() ignora repetição: o mesmo resultado duas vezes não muda nada.
            if (pedido.concluir("APROVADO".equals(resultado.status()), resultado.motivo(), resultado.instancia())) {
                repository.save(pedido);
                log.info("Pedido {} agora está {}", pedido.getId(), pedido.getStatus());
            } else {
                log.info("Pedido {} já estava concluído; resultado repetido ignorado", pedido.getId());
            }
        }, () -> log.warn("Resultado para pedido {} que não existe aqui", resultado.pedidoId()));
    }
}
