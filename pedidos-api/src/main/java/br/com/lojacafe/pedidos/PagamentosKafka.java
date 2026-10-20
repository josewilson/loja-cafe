package br.com.lojacafe.pedidos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Toda a conversa com o Kafka numa classe só (specs/eventos.md): cria os dois tópicos, anota em pedidos-criados que um pedido
 * nasceu e lê pagamentos-resultados para atualizar o status do pedido.
 */
@Component
public class PagamentosKafka {

    public static final String PEDIDOS_CRIADOS = "pedidos-criados";
    public static final String PAGAMENTOS_RESULTADOS = "pagamentos-resultados";

    record ItemEvento(String item, int quantidade) {}

    record PedidoCriado(int versao, Long pedidoId, Long lojaId, String cliente, List<ItemEvento> itens, BigDecimal valor,
                        FormaPagamento formaPagamento, Integer parcelas, Instant criadoEm) {}

    record ResultadoPagamento(int versao, Long pedidoId, String status, String motivo,
                              String instancia, String processadoEm, Pix pix) {}

    /** Cobrança Pix (status AGUARDANDO): specs/eventos.md. */
    record Pix(String copiaECola, String qrCode, Instant expiraEm) {}

    private static final Logger log = LoggerFactory.getLogger(PagamentosKafka.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final PedidoRepository repository;
    private final EstoqueLojaRepository estoque;
    private final FichaController consumo;

    public PagamentosKafka(KafkaTemplate<String, String> kafka, JsonMapper json, PedidoRepository repository,
                           EstoqueLojaRepository estoque, FichaController consumo) {
        this.kafka = kafka;
        this.json = json;
        this.repository = repository;
        this.estoque = estoque;
        this.consumo = consumo;
    }

    // Cria os dois tópicos do contrato (se ainda não existirem) com 3 partições. static: não precisa instanciar a classe para isso.
    @Bean
    static NewTopic pedidosCriados() {
        return TopicBuilder.name(PEDIDOS_CRIADOS).partitions(3).replicas(1).build();
    }

    @Bean
    static NewTopic pagamentosResultados() {
        return TopicBuilder.name(PAGAMENTOS_RESULTADOS).partitions(3).replicas(1).build();
    }

    // ---------- saída: pedidos-criados ----------

    public void publicarCriado(Pedido p) throws Exception {
        var itens = p.getItens().stream().map(i -> new ItemEvento(i.getItem(), i.getQuantidade())).toList();
        var evento = new PedidoCriado(3, p.getId(), p.getLojaId(), p.getCliente(), itens, p.getValor(),
                p.getFormaPagamento(), p.getParcelas(), p.getCriadoEm());
        // Chave = pedidoId: todos os eventos do mesmo pedido caem na mesma partição.
        // .get() espera o "ok" do broker; se o Kafka estiver fora, a falha aparece aqui.
        kafka.send(PEDIDOS_CRIADOS, String.valueOf(p.getId()), json.writeValueAsString(evento))
                .get(5, TimeUnit.SECONDS);
    }

    // ---------- entrada: pagamentos-resultados ----------

    @KafkaListener(topics = PAGAMENTOS_RESULTADOS)
    @Transactional
    public void receber(String mensagem) {
        var resultado = json.readValue(mensagem, ResultadoPagamento.class);
        // Com trava na linha: um cancelamento ao mesmo tempo espera a nossa vez (e vice-versa).
        repository.findWithLockById(resultado.pedidoId()).ifPresentOrElse(pedido -> {
            if ("AGUARDANDO".equals(resultado.status())) {
                // Cobrança Pix criada: guarda o QR para a tela mostrar. O pedido continua aguardando.
                var pix = resultado.pix();
                if (pix != null && pedido.registrarPix(pix.copiaECola(), pix.qrCode(), pix.expiraEm())) {
                    repository.save(pedido);
                    log.info("Pedido {} aguardando o Pix", pedido.getId());
                }
                return;
            }
            if (concluir(pedido, "APROVADO".equals(resultado.status()), resultado.motivo(), resultado.instancia())) {
                log.info("Pedido {} agora está {}", pedido.getId(), pedido.getStatus());
            } else {
                log.info("Pedido {} já estava concluído; resultado repetido ignorado", pedido.getId());
            }
        }, () -> log.warn("Resultado para pedido {} que não existe aqui", resultado.pedidoId()));
    }

    /**
     * Aplica a decisão de pagamento a um pedido (já lido com trava): vale a primeira, a repetição não muda nada.
     * Serve ao Kafka e à confirmação feita à mão no balcão (cartão na maquininha). Devolve false se já estava concluído.
     */
    public boolean concluir(Pedido pedido, boolean aprovado, String motivo, String instancia) {
        if (!pedido.concluir(aprovado, motivo, instancia)) {
            return false;
        }
        repository.save(pedido);
        if (pedido.getStatus() == StatusPedido.RECUSADO && pedido.getPreparo() != Preparo.CANCELADO) {
            // Só quem concluiu devolve: resultado repetido não repõe de novo.
            // Pedido que o gerente já cancelou também não repõe: o cancelamento já devolveu.
            pedido.getItens().forEach(i -> estoque.repor(pedido.getLojaId(), i.getProdutoId(), i.getQuantidade()));
            consumo.devolver(pedido); // recusado nunca foi preparado: os insumos voltam
        }
        return true;
    }
}
