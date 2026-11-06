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

class EstoqueEFiscalTest extends ApiTestBase {

    // ---------- cardápio e estoque por loja ----------

    @Test
    void cardapioEDaRedeEEstoqueEDaLoja() throws Exception {
        long a = novaLoja();
        long b = novaLoja();
        String atendenteA = token(Papel.ATENDENTE, a);
        String atendenteB = token(Papel.ATENDENTE, b);
        GET(atendenteA, "/produtos").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$[0].nome").value("Café coado"))
                .andExpect(jsonPath("$[0].preco").value(8.5));

        POST(atendenteA, "/pedidos", umItem(2, 5)).andExpect(status().isCreated());
        assertEquals(45, estoque(atendenteA, 2));
        assertEquals(50, estoque(atendenteB, 2)); // a outra loja não foi afetada
    }

    @Test
    void adminPrecisaDizerQualLojaParaVerOCardapio() throws Exception {
        long loja = novaLoja();
        GET(admin(), "/produtos").andExpect(status().isBadRequest());
        GET(admin(), "/produtos?lojaId=" + loja).andExpect(status().isOk()).andExpect(jsonPath("$[0].estoque").value(50));
        GET(token(Papel.ATENDENTE, loja), "/produtos?lojaId=" + (loja + 1000)).andExpect(status().isForbidden());
    }

    @Test
    void estoqueFaltandoDa400SemGravarNada() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        POST(t, "/pedidos", umItem(4, 30)).andExpect(status().isCreated());
        POST(t, "/pedidos", umItem(4, 30)).andExpect(status().isBadRequest()); // só restam 20
        assertEquals(20, estoque(t, 4));
        GET(t, "/pedidos").andExpect(jsonPath("$", hasSize(1)));
        // falta num item desfaz os outros
        POST(t, "/pedidos", pedido("[{\"produtoId\":3,\"quantidade\":1},{\"produtoId\":4,\"quantidade\":21}]")).andExpect(status().isBadRequest());
        assertEquals(50, estoque(t, 3));
    }

    @Test
    void gerenteAjustaOEstoqueDaPropriaLoja() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        PUT(gerente, "/lojas/" + loja + "/estoque/1", "{\"quantidade\":12}").andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidade").value(12));
        assertEquals(12, estoque(gerente, 1));
        PUT(gerente, "/lojas/" + outra + "/estoque/1", "{\"quantidade\":12}").andExpect(status().isForbidden());
        PUT(gerente, "/lojas/" + loja + "/estoque/1", "{\"quantidade\":-1}").andExpect(status().isBadRequest());
        PUT(gerente, "/lojas/" + loja + "/estoque/99", "{\"quantidade\":1}").andExpect(status().isNotFound());
        PUT(admin(), "/lojas/999999/estoque/1", "{\"quantidade\":1}").andExpect(status().isNotFound());
        PUT(admin(), "/lojas/" + outra + "/estoque/1", "{\"quantidade\":3}").andExpect(status().isOk());
    }

    @Test
    void pagamentoRecusadoDevolveOEstoqueDaLojaCertaUmaSoVez() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String t = token(Papel.ATENDENTE, loja);
        String corpo = POST(t, "/pedidos", umItem(3, 10)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        assertEquals(40, estoque(t, 3));

        String recusado = "{\"versao\":1,\"pedidoId\":" + id + ",\"status\":\"RECUSADO\",\"motivo\":\"limite\",\"instancia\":\"caixa-1\"}";
        listener.receber(recusado);
        assertEquals(50, estoque(t, 3));
        listener.receber(recusado); // evento repetido: não repõe de novo
        assertEquals(50, estoque(t, 3));
        assertEquals(50, estoque(token(Papel.ATENDENTE, outra), 3));
    }

    @Test
    void pagamentoAprovadoNaoMexeNoEstoque() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        String corpo = POST(t, "/pedidos", umItem(3, 2)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        listener.receber("{\"versao\":1,\"pedidoId\":" + id + ",\"status\":\"APROVADO\",\"motivo\":null,\"instancia\":\"caixa-1\"}");
        assertEquals(48, estoque(t, 3));
    }

    @Test
    void pixAguardandoGuardaOQrCodeEOPedidoContinuaAguardando() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        long id = lancar(t, 3, 1);
        listener.receber("{\"versao\":2,\"pedidoId\":" + id + ",\"status\":\"AGUARDANDO\",\"motivo\":null,\"instancia\":\"caixa-1\","
                + "\"pix\":{\"copiaECola\":\"00020126PIX\",\"qrCode\":\"QUJD\",\"expiraEm\":\"2030-01-01T10:00:00Z\"}}");
        GET(t, "/pedidos?limite=1").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("AGUARDANDO_PAGAMENTO"))
                .andExpect(jsonPath("$[0].pix.copiaECola").value("00020126PIX"))
                .andExpect(jsonPath("$[0].pix.qrCode").value("QUJD"));
        resultado(id, "APROVADO"); // pago: a cobrança some da resposta
        GET(t, "/pedidos?limite=1").andExpect(jsonPath("$[0].status").value("PAGO")).andExpect(jsonPath("$[0].pix").doesNotExist());
        // AGUARDANDO atrasado (depois do pagamento) não desfaz nada
        listener.receber("{\"versao\":2,\"pedidoId\":" + id + ",\"status\":\"AGUARDANDO\",\"pix\":{\"copiaECola\":\"X\",\"qrCode\":\"Y\",\"expiraEm\":\"2030-01-01T10:00:00Z\"}}");
        GET(t, "/pedidos?limite=1").andExpect(jsonPath("$[0].status").value("PAGO")).andExpect(jsonPath("$[0].pix").doesNotExist());
    }

    // ---------- dados fiscais e recibo (Fase 6) ----------

    @Test
    void cnpjDaLojaTemQueTerOsDigitosVerificadoresCertos() {
        org.junit.jupiter.api.Assertions.assertTrue(Loja.cnpjValido("11222333000181"));
        org.junit.jupiter.api.Assertions.assertFalse(Loja.cnpjValido("11222333000182")); // dígito errado
        org.junit.jupiter.api.Assertions.assertFalse(Loja.cnpjValido("11111111111111")); // repetido
        org.junit.jupiter.api.Assertions.assertFalse(Loja.cnpjValido("1122233300018"));  // 13 dígitos
        org.junit.jupiter.api.Assertions.assertFalse(Loja.cnpjValido(null));
    }

    @Test
    void adminGuardaOsDadosFiscaisDaLojaESoEle() throws Exception {
        long loja = novaLoja();
        String corpo = "{\"razaoSocial\":\"Café Central LTDA\",\"cnpj\":\"11222333000181\",\"inscricaoEstadual\":\"123456\",\"endereco\":\"Rua A, 10\"}";
        PUT(admin(), "/lojas/" + loja, corpo).andExpect(status().isOk())
                .andExpect(jsonPath("$.cnpj").value("11222333000181")).andExpect(jsonPath("$.razaoSocial").value("Café Central LTDA"));
        GET(admin(), "/lojas").andExpect(jsonPath("$[?(@.id==" + loja + ")].endereco").value("Rua A, 10"));
        PUT(admin(), "/lojas/" + loja, "{\"cnpj\":\"11222333000182\"}").andExpect(status().isBadRequest());   // dígito errado
        PUT(admin(), "/lojas/" + loja, "{\"cnpj\":\"123\"}").andExpect(status().isBadRequest());             // formato
        PUT(admin(), "/lojas/999999", corpo).andExpect(status().isNotFound());
        PUT(token(Papel.GERENTE, loja), "/lojas/" + loja, corpo).andExpect(status().isForbidden());
        // campo ausente = apagado
        PUT(admin(), "/lojas/" + loja, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.cnpj").doesNotExist());
    }

    @Test
    void produtoGuardaNcmECfopOpcionaisEValidados() throws Exception {
        String nome = "Produto fiscal " + UUID.randomUUID();
        var criado = POST(admin(), "/cardapio", "{\"nome\":\"" + nome + "\",\"emoji\":\"☕\",\"preco\":7.00,\"ncm\":\"21011110\",\"cfop\":\"5102\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ncm").value("21011110")).andExpect(jsonPath("$.cfop").value("5102"));
        String id = texto(criado, "$.id");
        POST(admin(), "/cardapio", "{\"nome\":\"X " + UUID.randomUUID() + "\",\"emoji\":\"☕\",\"preco\":7.00,\"ncm\":\"123\"}").andExpect(status().isBadRequest());
        POST(admin(), "/cardapio", "{\"nome\":\"Y " + UUID.randomUUID() + "\",\"emoji\":\"☕\",\"preco\":7.00,\"cfop\":\"51022\"}").andExpect(status().isBadRequest());
        PUT(admin(), "/cardapio/" + id, "{\"nome\":\"" + nome + "\",\"emoji\":\"☕\",\"preco\":7.00}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ncm").doesNotExist());
    }

    @Test
    void reciboSoDePedidoPagoESoDaPropriaLoja() throws Exception {
        long loja = novaLoja();
        PUT(admin(), "/lojas/" + loja, "{\"razaoSocial\":\"Café LTDA\",\"cnpj\":\"11222333000181\",\"endereco\":\"Rua A, 10\"}").andExpect(status().isOk());
        String t = token(Papel.ATENDENTE, loja);
        long aguardando = lancar(t, 3, 1);
        GET(t, "/pedidos/" + aguardando + "/recibo").andExpect(status().isConflict()); // ainda não pago

        long pago = lancarPago(t, 3, 2);
        GET(t, "/pedidos/" + pago + "/recibo").andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value(pago))
                .andExpect(jsonPath("$.aviso").value("Sem valor fiscal"))
                .andExpect(jsonPath("$.loja.cnpj").value("11222333000181"))
                .andExpect(jsonPath("$.loja.endereco").value("Rua A, 10"))
                .andExpect(jsonPath("$.itens[0].quantidade").value(2))
                .andExpect(jsonPath("$.formaPagamento").exists());
        GET(admin(), "/pedidos/" + pago + "/recibo").andExpect(status().isOk());                       // ADMIN vê qualquer loja
        GET(token(Papel.ATENDENTE, novaLoja()), "/pedidos/" + pago + "/recibo").andExpect(status().isForbidden()); // outra loja
        GET(t, "/pedidos/999999/recibo").andExpect(status().isNotFound());
        GET(null, "/pedidos/" + pago + "/recibo").andExpect(status().isUnauthorized());
        GET(token(Papel.GERENTE, loja), "/auditoria").andExpect(jsonPath("$[?(@.acao=='RECIBO_EMITIDO' && @.entidadeId=='" + pago + "')]").isNotEmpty());

        PUT(token(Papel.GERENTE, loja), "/pedidos/" + pago + "/cancelar", "{}").andExpect(status().isOk());
        GET(t, "/pedidos/" + pago + "/recibo").andExpect(status().isConflict());                       // cancelado não tem recibo
    }

}
