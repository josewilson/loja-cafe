package br.com.lojacafe.pedidos;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import br.com.lojacafe.pedidos.controller.SaudeController;
import br.com.lojacafe.pedidos.model.Insumo;
import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Produto;
import br.com.lojacafe.pedidos.service.PagamentosKafka;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class PagamentoTest extends ApiTestBase {

    // ---------- cartão na maquininha (Fase 7a) ----------

    private long lancarCartao(String token, String forma, int produtoId, int quantidade) throws Exception {
        String corpo = POST(token, "/pedidos", "{\"formaPagamento\":\"" + forma + "\",\"itens\":[{\"produtoId\":" + produtoId
                + ",\"quantidade\":" + quantidade + "}]}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    @Test
    void atendenteConfirmaOCartaoNaMaquininhaComNsu() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        long id = lancarCartao(t, "CREDITO", 3, 1);
        PUT(t, "/pedidos/" + id + "/pagamento", "{\"aprovado\":true,\"nsu\":\" 123456 \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAGO")).andExpect(jsonPath("$.nsu").value("123456"))
                .andExpect(jsonPath("$.processadoPor").value("maquininha"));
        assertEquals(49, estoque(t, 3));                                                         // venda concluída: o estoque continua baixado
        PUT(t, "/pedidos/" + id + "/pagamento", "{\"aprovado\":false}").andExpect(status().isConflict()); // já decidido
        resultado(id, "RECUSADO");                                                               // e o Pagamentos atrasado não desfaz
        GET(t, "/pedidos?limite=1").andExpect(jsonPath("$[0].status").value("PAGO"));
    }

    @Test
    void cartaoRecusadoNaMaquininhaDevolveOEstoque() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        long id = lancarCartao(t, "DEBITO", 3, 2);
        assertEquals(48, estoque(t, 3));
        PUT(t, "/pedidos/" + id + "/pagamento", "{\"aprovado\":false,\"nsu\":\"999\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECUSADO")).andExpect(jsonPath("$.motivo").value("recusado na maquininha"))
                .andExpect(jsonPath("$.nsu").doesNotExist());
        assertEquals(50, estoque(t, 3));
    }

    @Test
    void confirmacaoDeCartaoTemSuasRegras() throws Exception {
        long loja = novaLoja();
        String t = token(Papel.ATENDENTE, loja);
        long pix = lancar(t, 3, 1);                                  // Pix não se confirma à mão
        PUT(t, "/pedidos/" + pix + "/pagamento", "{\"aprovado\":true}").andExpect(status().isConflict());
        long cartao = lancarCartao(t, "CREDITO", 3, 1);
        PUT(null, "/pedidos/" + cartao + "/pagamento", "{\"aprovado\":true}").andExpect(status().isUnauthorized());
        PUT(token(Papel.COZINHA, loja), "/pedidos/" + cartao + "/pagamento", "{\"aprovado\":true}").andExpect(status().isForbidden());
        PUT(token(Papel.ATENDENTE, novaLoja()), "/pedidos/" + cartao + "/pagamento", "{\"aprovado\":true}").andExpect(status().isForbidden()); // outra loja
        PUT(t, "/pedidos/" + cartao + "/pagamento", "{\"aprovado\":true,\"nsu\":\"" + "9".repeat(31) + "\"}").andExpect(status().isBadRequest());
        PUT(t, "/pedidos/999999/pagamento", "{\"aprovado\":true}").andExpect(status().isNotFound());
        PUT(token(Papel.GERENTE, loja), "/pedidos/" + cartao + "/pagamento", "{\"aprovado\":true}").andExpect(status().isOk()); // gerente também pode
        GET(token(Papel.GERENTE, loja), "/auditoria").andExpect(jsonPath("$[?(@.acao=='PAGAMENTO_CONFIRMADO' && @.entidadeId=='" + cartao + "')]").isNotEmpty());
    }

    // ---------- NFC-e (Fase 6b) ----------

    /** Produto com NCM e CFOP e 10 unidades na loja. */
    private long produtoFiscal(long loja) throws Exception {
        String corpo = POST(admin(), "/cardapio", "{\"nome\":\"Fiscal " + UUID.randomUUID() + "\",\"emoji\":\"☕\",\"preco\":7.00,\"ncm\":\"21011110\",\"cfop\":\"5102\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        PUT(admin(), "/lojas/" + loja + "/estoque/" + id, "{\"quantidade\":10}").andExpect(status().isOk());
        return id;
    }

    @Test
    void emiteNfceSimuladaDePedidoPagoERepetirEhSeguro() throws Exception {
        long loja = novaLoja();
        String atendente = token(Papel.ATENDENTE, loja);
        long fiscal = produtoFiscal(loja);
        long id = lancarPago(atendente, (int) fiscal, 2);

        POST(atendente, "/pedidos/" + id + "/nfce", "").andExpect(status().isConflict());                 // loja sem CNPJ
        PUT(admin(), "/lojas/" + loja, "{\"cnpj\":\"11222333000181\"}").andExpect(status().isOk());
        long semNcm = lancarPago(atendente, 3, 1);
        POST(atendente, "/pedidos/" + semNcm + "/nfce", "").andExpect(status().isConflict());             // produto sem NCM/CFOP
        POST(atendente, "/pedidos/" + lancar(atendente, (int) fiscal, 1) + "/nfce", "").andExpect(status().isConflict()); // ainda não pago
        POST(token(Papel.ATENDENTE, novaLoja()), "/pedidos/" + id + "/nfce", "").andExpect(status().isForbidden()); // outra loja
        POST(atendente, "/pedidos/999999/nfce", "").andExpect(status().isNotFound());
        POST(null, "/pedidos/" + id + "/nfce", "").andExpect(status().isUnauthorized());
        POST(token(Papel.COZINHA, loja), "/pedidos/" + id + "/nfce", "").andExpect(status().isForbidden());

        POST(atendente, "/pedidos/" + id + "/nfce", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.nfceStatus").value("AUTORIZADA")).andExpect(jsonPath("$.nfceNumero").value(String.valueOf(id)));
        POST(atendente, "/pedidos/" + id + "/nfce", "").andExpect(status().isOk()).andExpect(jsonPath("$.nfceStatus").value("AUTORIZADA"));
        GET(token(Papel.GERENTE, loja), "/auditoria").andExpect(jsonPath("$[?(@.acao=='NFCE_EMITIDA' && @.entidadeId=='" + id + "')]", hasSize(1))); // só uma vez
    }

    @Test
    void cancelarPedidoComNfceCancelaANotaJunto() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        PUT(admin(), "/lojas/" + loja, "{\"cnpj\":\"11222333000181\"}").andExpect(status().isOk());
        long id = lancarPago(gerente, (int) produtoFiscal(loja), 1);
        POST(gerente, "/pedidos/" + id + "/nfce", "").andExpect(status().isOk());

        PUT(gerente, "/pedidos/" + id + "/cancelar", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.nfceStatus").value("CANCELADA"));
        GET(gerente, "/auditoria").andExpect(jsonPath("$[?(@.acao=='NFCE_CANCELADA' && @.entidadeId=='" + id + "')]", hasSize(1)));
        POST(gerente, "/pedidos/" + id + "/nfce", "").andExpect(status().isConflict());                    // cancelado: sem nota nova
    }

    // ---------- estorno (Fase 7b) ----------

    @Test
    void gerenteRegistraOEstornoDePedidoPagoECancelado() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String atendente = token(Papel.ATENDENTE, loja);
        long id = lancarPago(atendente, 3, 1);
        PUT(gerente, "/pedidos/" + id + "/estorno", "{}").andExpect(status().isConflict());          // ainda não cancelado
        PUT(gerente, "/pedidos/" + id + "/cancelar", "{}").andExpect(status().isOk());
        PUT(atendente, "/pedidos/" + id + "/estorno", "{}").andExpect(status().isForbidden());       // só o gerente
        PUT(token(Papel.GERENTE, novaLoja()), "/pedidos/" + id + "/estorno", "{}").andExpect(status().isForbidden()); // outra loja
        PUT(gerente, "/pedidos/" + id + "/estorno", "{\"motivo\":\"devolvido no Pix\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.estornadoEm").isNotEmpty());
        PUT(gerente, "/pedidos/" + id + "/estorno", "{}").andExpect(status().isConflict());          // só uma vez
        GET(gerente, "/auditoria").andExpect(jsonPath("$[?(@.acao=='PAGAMENTO_ESTORNADO' && @.entidadeId=='" + id + "')].detalhe", contains("devolvido no Pix")));
        PUT(gerente, "/pedidos/999999/estorno", "{}").andExpect(status().isNotFound());
    }

    @Test
    void cancelarPixPagoPedeOEstornoEORetornoDoProvedorRegistra() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long pix = lancarPago(token(Papel.ATENDENTE, loja), 3, 1);
        PUT(gerente, "/pedidos/" + pix + "/cancelar", "{}").andExpect(status().isOk());
        org.mockito.Mockito.verify(eventos).publicarEstorno(org.mockito.ArgumentMatchers.argThat(p -> p.getId() == pix));
        resultado(pix, "ESTORNO_FALHOU");                                                             // provedor falhou: nada muda
        GET(gerente, "/pedidos?limite=1").andExpect(jsonPath("$[0].estornadoEm").doesNotExist());
        resultado(pix, "ESTORNADO");
        GET(gerente, "/pedidos?limite=1").andExpect(jsonPath("$[0].estornadoEm").isNotEmpty());
        resultado(pix, "ESTORNADO");                                                                  // repetido: sem efeito
        GET(gerente, "/auditoria").andExpect(jsonPath("$[?(@.acao=='PAGAMENTO_ESTORNADO' && @.entidadeId=='" + pix + "')]", hasSize(1)));
        PUT(gerente, "/pedidos/" + pix + "/estorno", "{}").andExpect(status().isConflict());         // já estornado
    }

    @Test
    void cancelarOQueNaoEPixPagoNaoPedeEstornoAoProvedor() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long dinheiro = pagoComo(token(Papel.ATENDENTE, loja), "DINHEIRO", 3, 1);
        long aguardando = lancar(token(Papel.ATENDENTE, loja), 3, 1);
        PUT(gerente, "/pedidos/" + dinheiro + "/cancelar", "{}").andExpect(status().isOk());
        PUT(gerente, "/pedidos/" + aguardando + "/cancelar", "{}").andExpect(status().isOk());
        org.mockito.Mockito.verify(eventos, org.mockito.Mockito.never()).publicarEstorno(org.mockito.ArgumentMatchers.any());
        resultado(aguardando, "ESTORNADO");                                                           // evento fora de hora: ignorado
        GET(gerente, "/pedidos?limite=1").andExpect(jsonPath("$[0].estornadoEm").doesNotExist());
    }

    @Test
    void pedidoNaoPagoNaoTemEstorno() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long aguardando = lancar(token(Papel.ATENDENTE, loja), 3, 1);
        PUT(gerente, "/pedidos/" + aguardando + "/cancelar", "{}").andExpect(status().isOk());      // cancelado sem nunca ter sido pago
        PUT(gerente, "/pedidos/" + aguardando + "/estorno", "{}").andExpect(status().isConflict());
    }

}
