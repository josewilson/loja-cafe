package br.com.lojacafe.pedidos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Ato 3: lê pagamentos-resultados e atualiza o status do pedido. */
@Component
public class ResultadoPagamentoListener {

    record ResultadoPagamento(int versao, Long pedidoId, String status, String motivo,
                              String instancia, String processadoEm) {}

    private static final Logger log = LoggerFactory.getLogger(ResultadoPagamentoListener.class);

    private final PedidoRepository repository;
    private final EstoqueLojaRepository estoque;
    private final JsonMapper json;

    public ResultadoPagamentoListener(PedidoRepository repository, EstoqueLojaRepository estoque, JsonMapper json) {
        this.repository = repository;
        this.estoque = estoque;
        this.json = json;
    }

    @KafkaListener(topics = KafkaTopicos.PAGAMENTOS_RESULTADOS)
    @Transactional
    public void receber(String mensagem) {
        var resultado = json.readValue(mensagem, ResultadoPagamento.class);
        repository.findById(resultado.pedidoId()).ifPresentOrElse(pedido -> {
            // concluir() ignora repetição: o mesmo resultado duas vezes não muda nada.
            if (pedido.concluir("APROVADO".equals(resultado.status()), resultado.motivo(), resultado.instancia())) {
                repository.save(pedido);
                if (pedido.getStatus() == StatusPedido.RECUSADO) {
                    // Só quem concluiu devolve: resultado repetido cai no "else" e não repõe de novo.
                    pedido.getItens().forEach(i -> estoque.repor(pedido.getLojaId(), i.getProdutoId(), i.getQuantidade()));
                }
                log.info("Pedido {} agora está {}", pedido.getId(), pedido.getStatus());
            } else {
                log.info("Pedido {} já estava concluído; resultado repetido ignorado", pedido.getId());
            }
        }, () -> log.warn("Resultado para pedido {} que não existe aqui", resultado.pedidoId()));
    }
}
