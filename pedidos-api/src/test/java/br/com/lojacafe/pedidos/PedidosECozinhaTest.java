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

class PedidosECozinhaTest extends ApiTestBase {

    // ---------- pedidos ----------

    @Test
    void postValidoCalculaOValorNoServidor() throws Exception {
        long loja = novaLoja();
        String t = token(Papel.ATENDENTE, loja);
        // Cappuccino 12,00 + Grande 3,00 + Chantilly 2,00 + Canela 0,50 = 17,50 × 2 = 35,00; mais 1 café coado 8,50
        POST(t, "/pedidos", """
                {"cliente":"Ana","formaPagamento":"CREDITO","parcelas":2,"observacao":"sem açúcar",
                 "itens":[{"produtoId":2,"quantidade":2,"tamanho":"GRANDE","extras":["CHANTILLY","CANELA"]},
                          {"produtoId":1,"quantidade":1}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lojaId").value(loja))
                .andExpect(jsonPath("$.cliente").value("Ana"))
                .andExpect(jsonPath("$.atendente").value("ATENDENTE"))
                .andExpect(jsonPath("$.itens", hasSize(2)))
                .andExpect(jsonPath("$.itens[0].item").value("Cappuccino"))
                .andExpect(jsonPath("$.itens[0].valor").value(35.0))
                .andExpect(jsonPath("$.itens[0].extras", contains("CHANTILLY", "CANELA")))
                .andExpect(jsonPath("$.valor").value(43.5))
                .andExpect(jsonPath("$.formaPagamento").value("CREDITO"))
                .andExpect(jsonPath("$.parcelas").value(2))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"))
                .andExpect(jsonPath("$.observacao").value("sem açúcar"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void clienteDoBalcaoEOpcional() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        POST(t, "/pedidos", umItem(1, 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.cliente").value("Balcão"));
        POST(t, "/pedidos", "{\"cliente\":\"  Carla \",\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cliente").value("Carla"));
    }

    @Test
    void precoEnviadoPeloClienteEIgnorado() throws Exception {
        POST(token(Papel.ATENDENTE, novaLoja()), "/pedidos", pedido("[{\"produtoId\":1,\"quantidade\":1,\"precoUnitario\":0.01}]"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.valor").value(8.5));
    }

    @Test
    void entradasInvalidasDao400() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        POST(t, "/pedidos", umItem(99, 1)).andExpect(status().isBadRequest());
        POST(t, "/pedidos", umItem(1, 0)).andExpect(status().isBadRequest());
        POST(t, "/pedidos", umItem(1, 51)).andExpect(status().isBadRequest());
        POST(t, "/pedidos", pedido("[]")).andExpect(status().isBadRequest());
        POST(t, "/pedidos", "{\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}").andExpect(status().isBadRequest());
        POST(t, "/pedidos", "{\"formaPagamento\":\"FIADO\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}").andExpect(status().isBadRequest());
        POST(t, "/pedidos", pedido("[{\"produtoId\":1,\"quantidade\":1,\"tamanho\":\"XG\"}]")).andExpect(status().isBadRequest());
        POST(t, "/pedidos", pedido("[{\"produtoId\":1,\"quantidade\":1,\"extras\":[\"GELO\"]}]")).andExpect(status().isBadRequest());
        POST(t, "/pedidos", "{\"cliente\":\"" + "A".repeat(61) + "\",\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}")
                .andExpect(status().isBadRequest());
        POST(t, "/pedidos", "{\"formaPagamento\":\"PIX\",\"observacao\":\"" + "x".repeat(141)
                + "\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}").andExpect(status().isBadRequest());
        POST(t, "/pedidos", "{\"formaPagamento\":").andExpect(status().isBadRequest());
    }

    @Test
    void parcelasAcimaDoPermitidoDao400() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        String item = "[{\"produtoId\":1,\"quantidade\":1}]";
        POST(t, "/pedidos", "{\"formaPagamento\":\"PIX\",\"parcelas\":2,\"itens\":" + item + "}").andExpect(status().isBadRequest());
        POST(t, "/pedidos", "{\"formaPagamento\":\"CREDITO\",\"parcelas\":3,\"itens\":" + item + "}").andExpect(status().isCreated());
        POST(t, "/pedidos", "{\"formaPagamento\":\"DINHEIRO\",\"itens\":" + item + "}").andExpect(status().isCreated());
    }

    @Test
    void erroDeValidacaoDizQualCampoFalhou() throws Exception {
        // O corpo JSON do erro vem do /error, que o MockMvc não percorre: aqui olhamos a exceção.
        // O corpo real (com "errors") foi conferido com a API no ar.
        POST(token(Papel.ATENDENTE, novaLoja()), "/pedidos", pedido("[{\"produtoId\":1,\"quantidade\":0}]"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    var erro = (org.springframework.web.bind.MethodArgumentNotValidException) result.getResolvedException();
                    var campos = erro.getBindingResult().getFieldErrors().stream().map(e -> e.getField()).toList();
                    org.junit.jupiter.api.Assertions.assertTrue(campos.contains("itens[0].quantidade"), "campos: " + campos);
                });
    }

    @Test
    void cadaLojaSoVeOsSeusPedidos() throws Exception {
        long a = novaLoja();
        long b = novaLoja();
        String atendenteA = token(Papel.ATENDENTE, a);
        String cozinhaA = token(Papel.COZINHA, a);
        String atendenteB = token(Papel.ATENDENTE, b);
        POST(atendenteA, "/pedidos", umItem(1, 1)).andExpect(status().isCreated());
        POST(atendenteA, "/pedidos", umItem(2, 1)).andExpect(status().isCreated());
        POST(atendenteB, "/pedidos", umItem(1, 1)).andExpect(status().isCreated());

        GET(atendenteA, "/pedidos").andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itens[0].item").value("Cappuccino")) // mais novo primeiro
                .andExpect(jsonPath("$[*].lojaId", everyItem(is((int) a))));
        GET(cozinhaA, "/pedidos").andExpect(jsonPath("$", hasSize(2))); // a cozinha vê os da mesma loja
        GET(atendenteA, "/pedidos?limite=1").andExpect(jsonPath("$", hasSize(1)));
        GET(atendenteB, "/pedidos").andExpect(jsonPath("$", hasSize(1)));
        GET(atendenteA, "/pedidos?lojaId=" + b).andExpect(status().isForbidden());
        GET(atendenteA, "/pedidos?lojaId=" + a).andExpect(status().isOk());

        // o ADMIN vê todas, ou filtra
        GET(admin(), "/pedidos?lojaId=" + b).andExpect(jsonPath("$", hasSize(1)));
        GET(admin(), "/pedidos?limite=200").andExpect(jsonPath("$[*].lojaId", hasItems((int) a, (int) b)));
    }

    // ---------- auditoria ----------

    @Test
    void auditoriaRegistraQuemFezOQuePorLoja() throws Exception {
        long a = novaLoja();
        long b = novaLoja();
        String atendenteA = token(Papel.ATENDENTE, a);
        String gerenteA = token(Papel.GERENTE, a);
        String atendenteB = token(Papel.ATENDENTE, b);
        POST(atendenteA, "/pedidos", umItem(1, 1)).andExpect(status().isCreated());
        POST(atendenteB, "/pedidos", umItem(1, 1)).andExpect(status().isCreated());
        PUT(gerenteA, "/lojas/" + a + "/estoque/1", "{\"quantidade\":9}").andExpect(status().isOk());
        entrar("ninguem@exemplo.com", SENHA).andExpect(status().isUnauthorized());

        // o gerente só enxerga a própria loja
        GET(gerenteA, "/auditoria").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].lojaId", everyItem(is((int) a))))
                .andExpect(jsonPath("$[?(@.acao=='PEDIDO_CRIADO')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.acao=='ESTOQUE_AJUSTADO')].detalhe", contains(containsString("49 -> 9"))))
                .andExpect(jsonPath("$[?(@.acao=='LOGIN')]", not(empty())))
                .andExpect(jsonPath("$[?(@.acao=='USUARIO_CRIADO')]", not(empty())));
        GET(gerenteA, "/auditoria?lojaId=" + b).andExpect(status().isForbidden());

        // o admin vê tudo e filtra por loja
        GET(admin(), "/auditoria?lojaId=" + a + "&limite=500").andExpect(jsonPath("$[?(@.acao=='LOJA_CRIADA')]", hasSize(1)));
        GET(admin(), "/auditoria?limite=500").andExpect(jsonPath("$[?(@.acao=='LOGIN_FALHOU')]", not(empty())));
        GET(admin(), "/auditoria?limite=1").andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- cozinha, retirada e cancelamento ----------


    @Test
    void pedidoNasceNaFila() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        POST(t, "/pedidos", umItem(1, 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.preparo").value("NA_FILA"));
    }

    @Test
    void cozinhaSoVePedidosPagosDaPropriaLoja() throws Exception {
        long a = novaLoja();
        long b = novaLoja();
        String atendente = token(Papel.ATENDENTE, a);
        String cozinhaA = token(Papel.COZINHA, a);
        String cozinhaB = token(Papel.COZINHA, b);
        long id = lancar(atendente, 1, 1);

        GET(cozinhaA, "/cozinha").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0))); // ainda não pago
        resultado(id, "APROVADO");
        GET(cozinhaA, "/cozinha").andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(id));
        GET(atendente, "/cozinha").andExpect(jsonPath("$", hasSize(1))); // o balcão também enxerga a fila
        GET(cozinhaB, "/cozinha").andExpect(jsonPath("$", hasSize(0)));
        GET(cozinhaA, "/cozinha?lojaId=" + b).andExpect(status().isForbidden());
        GET(admin(), "/cozinha").andExpect(status().isBadRequest());
        GET(admin(), "/cozinha?lojaId=" + a).andExpect(jsonPath("$", hasSize(1)));

        long recusado = lancar(atendente, 1, 1);
        resultado(recusado, "RECUSADO");
        GET(cozinhaA, "/cozinha").andExpect(jsonPath("$", hasSize(1))); // recusado nunca entra na fila
    }

    @Test
    void filaVaiDoMaisAntigoAoMaisNovo() throws Exception {
        String atendente = token(Papel.ATENDENTE, novaLoja());
        long primeiro = lancarPago(atendente, 1, 1);
        long segundo = lancarPago(atendente, 1, 1);
        GET(atendente, "/cozinha").andExpect(jsonPath("$[0].id").value(primeiro)).andExpect(jsonPath("$[1].id").value(segundo));
    }

    @Test
    void fluxoDePreparoAteRetirar() throws Exception {
        long loja = novaLoja();
        String atendente = token(Papel.ATENDENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);
        long id = lancarPago(atendente, 1, 1);

        preparo(cozinha, id, "PREPARANDO").andExpect(status().isOk()).andExpect(jsonPath("$.preparo").value("PREPARANDO"));
        preparo(cozinha, id, "PRONTO").andExpect(status().isOk()).andExpect(jsonPath("$.preparo").value("PRONTO"));
        GET(cozinha, "/cozinha").andExpect(jsonPath("$[0].preparo").value("PRONTO")); // pronto ainda aparece, até retirarem
        preparo(atendente, id, "RETIRADO").andExpect(status().isOk()).andExpect(jsonPath("$.preparo").value("RETIRADO"));
        GET(cozinha, "/cozinha").andExpect(jsonPath("$", hasSize(0)));
        GET(atendente, "/pedidos").andExpect(jsonPath("$[0].preparo").value("RETIRADO"));
    }

    @Test
    void preparoTemRegras() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String atendente = token(Papel.ATENDENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);
        String cozinhaOutra = token(Papel.COZINHA, outra);
        long aguardando = lancar(atendente, 1, 1);
        long pago = lancarPago(atendente, 1, 1);

        preparo(cozinha, aguardando, "PREPARANDO").andExpect(status().isConflict()); // ainda não pago
        preparo(cozinha, pago, "PRONTO").andExpect(status().isConflict()); // pulou uma etapa
        preparo(cozinha, pago, "RETIRADO").andExpect(status().isForbidden()); // cozinha não entrega
        preparo(atendente, pago, "PREPARANDO").andExpect(status().isForbidden()); // balcão não prepara
        preparo(cozinhaOutra, pago, "PREPARANDO").andExpect(status().isForbidden()); // outra loja
        preparo(cozinha, pago, "NA_FILA").andExpect(status().isBadRequest());
        preparo(cozinha, pago, "CANCELADO").andExpect(status().isBadRequest());
        preparo(cozinha, 999999, "PREPARANDO").andExpect(status().isNotFound());
        PUT(cozinha, "/pedidos/" + pago + "/preparo", "{\"preparo\":\"BRINCANDO\"}").andExpect(status().isBadRequest());
        PUT(cozinha, "/pedidos/" + pago + "/preparo", "{}").andExpect(status().isBadRequest());
        preparo(admin(), pago, "PREPARANDO").andExpect(status().isForbidden());

        preparo(cozinha, pago, "PREPARANDO").andExpect(status().isOk());
        preparo(cozinha, pago, "PREPARANDO").andExpect(status().isConflict()); // já está preparando
    }

    @Test
    void gerenteTocaTodasAsEtapas() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long id = lancarPago(gerente, 1, 1);
        preparo(gerente, id, "PREPARANDO").andExpect(status().isOk());
        preparo(gerente, id, "PRONTO").andExpect(status().isOk());
        preparo(gerente, id, "RETIRADO").andExpect(status().isOk());
    }

    @Test
    void gerenteCancelaEDevolveOEstoqueUmaSoVez() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String atendente = token(Papel.ATENDENTE, loja);
        long id = lancarPago(atendente, 3, 10);
        assertEquals(40, estoque(gerente, 3));

        PUT(gerente, "/pedidos/" + id + "/cancelar", "{\"motivo\":\"cliente desistiu\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.preparo").value("CANCELADO"));
        assertEquals(50, estoque(gerente, 3));
        PUT(gerente, "/pedidos/" + id + "/cancelar", "{}").andExpect(status().isConflict()); // já cancelado
        assertEquals(50, estoque(gerente, 3)); // não devolveu de novo
        GET(atendente, "/cozinha").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cancelarSemCorpoTambemFunciona() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        long id = lancarPago(gerente, 1, 1);
        mvc.perform(put("/pedidos/" + id + "/cancelar").header("Authorization", "Bearer " + gerente))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preparo").value("CANCELADO"));
    }

    @Test
    void cancelamentoTemRegras() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String atendente = token(Papel.ATENDENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);
        String gerenteOutra = token(Papel.GERENTE, outra);

        long pago = lancarPago(atendente, 1, 1);
        PUT(atendente, "/pedidos/" + pago + "/cancelar", "{}").andExpect(status().isForbidden()); // só o gerente cancela
        PUT(cozinha, "/pedidos/" + pago + "/cancelar", "{}").andExpect(status().isForbidden());
        PUT(admin(), "/pedidos/" + pago + "/cancelar", "{}").andExpect(status().isForbidden());
        PUT(gerenteOutra, "/pedidos/" + pago + "/cancelar", "{}").andExpect(status().isForbidden()); // outra loja
        PUT(gerente, "/pedidos/999999/cancelar", "{}").andExpect(status().isNotFound());
        PUT(gerente, "/pedidos/" + pago + "/cancelar", "{\"motivo\":\"" + "x".repeat(141) + "\"}").andExpect(status().isBadRequest());

        // preparando ainda cancela; depois de PRONTO ou RETIRADO não cancela mais
        preparo(cozinha, pago, "PREPARANDO").andExpect(status().isOk());
        PUT(gerente, "/pedidos/" + pago + "/cancelar", "{}").andExpect(status().isOk());
        long pronto = lancarPago(atendente, 1, 1);
        preparo(cozinha, pronto, "PREPARANDO").andExpect(status().isOk());
        preparo(cozinha, pronto, "PRONTO").andExpect(status().isOk());
        PUT(gerente, "/pedidos/" + pronto + "/cancelar", "{}").andExpect(status().isConflict());
        preparo(atendente, pronto, "RETIRADO").andExpect(status().isOk());
        PUT(gerente, "/pedidos/" + pronto + "/cancelar", "{}").andExpect(status().isConflict());

        // recusado não tem o que cancelar (o estoque já voltou)
        long recusado = lancar(atendente, 1, 1);
        resultado(recusado, "RECUSADO");
        PUT(gerente, "/pedidos/" + recusado + "/cancelar", "{}").andExpect(status().isConflict());
    }

    @Test
    void cancelarAntesDoPagamentoNaoDevolveEmDobroSeDepoisForRecusado() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        long id = lancar(gerente, 3, 10); // ainda aguardando pagamento
        assertEquals(40, estoque(gerente, 3));

        PUT(gerente, "/pedidos/" + id + "/cancelar", "{}").andExpect(status().isOk());
        assertEquals(50, estoque(gerente, 3)); // o cancelamento devolveu
        resultado(id, "RECUSADO"); // o resultado chega depois
        assertEquals(50, estoque(gerente, 3)); // e não devolve de novo
    }

    @Test
    void auditoriaGuardaPreparoECancelamento() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);
        long a = lancarPago(gerente, 1, 1);
        long b = lancarPago(gerente, 1, 1);
        preparo(cozinha, a, "PREPARANDO").andExpect(status().isOk());
        PUT(gerente, "/pedidos/" + b + "/cancelar", "{\"motivo\":\"errei o pedido\"}").andExpect(status().isOk());

        GET(gerente, "/auditoria").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.acao=='PREPARO_ALTERADO')].detalhe", contains("NA_FILA -> PREPARANDO")))
                .andExpect(jsonPath("$[?(@.acao=='PEDIDO_CANCELADO')].detalhe", contains("errei o pedido")))
                .andExpect(jsonPath("$[?(@.acao=='PEDIDO_CANCELADO')].entidadeId", contains(String.valueOf(b))));
    }

}
