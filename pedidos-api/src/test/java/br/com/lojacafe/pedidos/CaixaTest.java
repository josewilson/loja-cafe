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

class CaixaTest extends ApiTestBase {

    // ---------- painel do gerente: caixa e vendas ----------

    private ResultActions fechar(String token, String contado) throws Exception {
        return POST(token, "/caixa/fechamentos", "{\"dinheiroContado\":" + contado + ",\"observacao\":\"turno\"}");
    }

    @Test
    void resumoDoCaixaAgrupaOTurnoAbertoPorForma() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        pagoComo(gerente, "PIX", 1, 2); // 17,00
        pagoComo(gerente, "DINHEIRO", 2, 1); // 12,00
        pagoComo(gerente, "CREDITO", 3, 1); // 6,00
        lancar(gerente, 1, 1); // aguardando: ainda não conta
        resultado(lancar(gerente, 1, 1), "RECUSADO"); // recusado: nunca conta
        long cancelado = pagoComo(gerente, "PIX", 1, 1);
        PUT(gerente, "/pedidos/" + cancelado + "/cancelar", "{}").andExpect(status().isOk()); // cancelado: não conta

        GET(gerente, "/caixa/resumo").andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidos").value(3))
                .andExpect(jsonPath("$.total").value(35.0))
                .andExpect(jsonPath("$.aguardando").value(1))
                .andExpect(jsonPath("$.desde").doesNotExist()) // a loja nunca fechou
                .andExpect(jsonPath("$.porForma[*].forma", contains("PIX", "DINHEIRO", "CREDITO"))) // do maior para o menor
                .andExpect(jsonPath("$.porForma[0].total").value(17.0));
    }

    @Test
    void fecharOCaixaCalculaADiferencaEZeraOTurno() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        pagoComo(gerente, "PIX", 1, 2); // 17,00
        pagoComo(gerente, "DINHEIRO", 2, 1); // 12,00 em dinheiro

        String primeiro = fechar(gerente, "11.50").andExpect(status().isCreated())
                .andExpect(jsonPath("$.pedidos").value(2))
                .andExpect(jsonPath("$.total").value(29.0))
                .andExpect(jsonPath("$.dinheiroEsperado").value(12.0))
                .andExpect(jsonPath("$.dinheiroContado").value(11.5))
                .andExpect(jsonPath("$.diferenca").value(-0.5)) // faltou 0,50
                .andExpect(jsonPath("$.porForma", hasSize(2)))
                .andExpect(jsonPath("$.observacao").value("turno"))
                .andExpect(jsonPath("$.desde").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        GET(gerente, "/caixa/resumo").andExpect(jsonPath("$.pedidos").value(0)).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.desde").value(JsonPath.read(primeiro, "$.ate").toString())); // o turno novo começa onde o outro acabou

        pagoComo(gerente, "DINHEIRO", 1, 1); // 8,50 no turno seguinte
        fechar(gerente, "9.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.pedidos").value(1))
                .andExpect(jsonPath("$.total").value(8.5))
                .andExpect(jsonPath("$.diferenca").value(0.5)) // sobrou 0,50
                .andExpect(jsonPath("$.desde").value(JsonPath.read(primeiro, "$.ate").toString()));
    }

    @Test
    void pedidoPagoDepoisDoFechamentoEntraNoProximoSemSePerder() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        long aguardando = lancar(gerente, 1, 2); // 17,00, pagamento ainda sem resposta
        fechar(gerente, "0").andExpect(status().isCreated()).andExpect(jsonPath("$.pedidos").value(0));

        resultado(aguardando, "APROVADO"); // o pagamento aprova só depois do fechamento
        GET(gerente, "/caixa/resumo").andExpect(jsonPath("$.pedidos").value(1)).andExpect(jsonPath("$.total").value(17.0));
        fechar(gerente, "0").andExpect(status().isCreated()).andExpect(jsonPath("$.pedidos").value(1)).andExpect(jsonPath("$.total").value(17.0));
        fechar(gerente, "0").andExpect(jsonPath("$.pedidos").value(0)); // e não conta duas vezes
    }

    @Test
    void pedidoJaFechadoNoCaixaNaoPodeSerCancelado() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        long antes = pagoComo(gerente, "PIX", 1, 1);
        fechar(gerente, "0").andExpect(status().isCreated());
        PUT(gerente, "/pedidos/" + antes + "/cancelar", "{}").andExpect(status().isConflict());

        long depois = pagoComo(gerente, "PIX", 1, 1); // do turno aberto ainda cancela
        PUT(gerente, "/pedidos/" + depois + "/cancelar", "{}").andExpect(status().isOk());
    }

    @Test
    void caixaTemRegrasDePapelEDeValidacao() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String atendente = token(Papel.ATENDENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);

        for (String t : new String[]{atendente, cozinha, admin()}) {
            fechar(t, "0").andExpect(status().isForbidden()); // só o gerente fecha o caixa
        }
        for (String t : new String[]{atendente, cozinha}) {
            GET(t, "/caixa/resumo").andExpect(status().isForbidden());
            GET(t, "/caixa/fechamentos").andExpect(status().isForbidden());
            GET(t, "/relatorios/vendas").andExpect(status().isForbidden());
        }
        GET(gerente, "/caixa/resumo?lojaId=" + outra).andExpect(status().isForbidden());
        GET(admin(), "/caixa/resumo").andExpect(status().isBadRequest()); // o admin precisa dizer a loja
        GET(admin(), "/caixa/resumo?lojaId=" + loja).andExpect(status().isOk());

        POST(gerente, "/caixa/fechamentos", "{}").andExpect(status().isBadRequest()); // sem dinheiroContado
        fechar(gerente, "-1").andExpect(status().isBadRequest());
        fechar(gerente, "10.001").andExpect(status().isBadRequest()); // só duas casas decimais
        fechar(gerente, "10000000.01").andExpect(status().isBadRequest());
        POST(gerente, "/caixa/fechamentos", "{\"dinheiroContado\":1,\"observacao\":\"" + "x".repeat(141) + "\"}").andExpect(status().isBadRequest());
        POST(gerente, "/caixa/fechamentos", "{\"dinheiroContado\":\"abc\"}").andExpect(status().isBadRequest());
    }

    @Test
    void historicoDeFechamentosVaiDoMaisNovoAoMaisAntigoEFicaNaLoja() throws Exception {
        long a = novaLoja();
        long b = novaLoja();
        String gerenteA = token(Papel.GERENTE, a);
        String gerenteB = token(Papel.GERENTE, b);
        pagoComo(gerenteA, "PIX", 1, 1);
        fechar(gerenteA, "0").andExpect(status().isCreated());
        pagoComo(gerenteA, "PIX", 2, 1);
        fechar(gerenteA, "0").andExpect(status().isCreated());
        pagoComo(gerenteB, "PIX", 3, 1); // o turno da loja B não é tocado pelo fechamento da A
        GET(gerenteB, "/caixa/resumo").andExpect(jsonPath("$.pedidos").value(1));

        GET(gerenteA, "/caixa/fechamentos").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].total").value(12.0)) // o último fechamento primeiro
                .andExpect(jsonPath("$[1].total").value(8.5))
                .andExpect(jsonPath("$[*].lojaId", everyItem(is((int) a))));
        GET(gerenteA, "/caixa/fechamentos?limite=1").andExpect(jsonPath("$", hasSize(1)));
        GET(gerenteB, "/caixa/fechamentos").andExpect(jsonPath("$", hasSize(0)));
        GET(gerenteB, "/caixa/fechamentos?lojaId=" + a).andExpect(status().isForbidden());
        GET(admin(), "/caixa/fechamentos?lojaId=" + a).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void fecharOCaixaVaiParaAAuditoria() throws Exception {
        String gerente = token(Papel.GERENTE, novaLoja());
        pagoComo(gerente, "DINHEIRO", 1, 1); // 8,50
        fechar(gerente, "8.00").andExpect(status().isCreated());
        GET(gerente, "/auditoria").andExpect(jsonPath("$[?(@.acao=='CAIXA_FECHADO')].detalhe",
                contains(allOf(containsString("esperado 8.50"), containsString("contado 8.00"), containsString("diferença -0.50")))));
    }

    @Test
    void vendasDoDia() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        pagoComo(gerente, "PIX", 1, 2); // 17,00
        pagoComo(gerente, "DINHEIRO", 2, 3); // 36,00
        lancar(gerente, 1, 1); // aguardando
        resultado(lancar(gerente, 1, 1), "RECUSADO");
        long cancelado = pagoComo(gerente, "PIX", 3, 1);
        PUT(gerente, "/pedidos/" + cancelado + "/cancelar", "{}").andExpect(status().isOk());

        GET(gerente, "/relatorios/vendas").andExpect(status().isOk())
                .andExpect(jsonPath("$.lojaId").value(loja))
                .andExpect(jsonPath("$.pedidos").value(2))
                .andExpect(jsonPath("$.total").value(53.0))
                .andExpect(jsonPath("$.ticketMedio").value(26.5))
                .andExpect(jsonPath("$.porForma[*].forma", contains("DINHEIRO", "PIX")))
                .andExpect(jsonPath("$.maisVendidos[*].item", contains("Cappuccino", "Café coado"))) // 3 cappuccinos, 2 cafés
                .andExpect(jsonPath("$.maisVendidos[0].quantidade").value(3))
                .andExpect(jsonPath("$.maisVendidos[0].total").value(36.0))
                .andExpect(jsonPath("$.recusados").value(1))
                .andExpect(jsonPath("$.cancelados").value(1))
                .andExpect(jsonPath("$.aguardando").value(1))
                .andExpect(jsonPath("$.estornados").value(0))
                .andExpect(jsonPath("$.estornosPendentes").value(1));  // cancelado depois de pago e ainda não devolvido
        resultado(cancelado, "ESTORNADO");
        GET(gerente, "/relatorios/vendas").andExpect(jsonPath("$.estornados").value(1)).andExpect(jsonPath("$.estornosPendentes").value(0));
    }

    @Test
    void vendasDeOutroDiaOuDeOutraLojaVeemZero() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        pagoComo(gerente, "PIX", 1, 1);
        GET(gerente, "/relatorios/vendas?data=2020-01-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("2020-01-01"))
                .andExpect(jsonPath("$.pedidos").value(0)).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.ticketMedio").value(0)).andExpect(jsonPath("$.maisVendidos", hasSize(0)));
        GET(token(Papel.GERENTE, novaLoja()), "/relatorios/vendas").andExpect(jsonPath("$.pedidos").value(0)); // outra loja
        GET(admin(), "/relatorios/vendas?lojaId=" + loja).andExpect(jsonPath("$.pedidos").value(1));
    }

    @Test
    void vendasValidamAData() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        GET(gerente, "/relatorios/vendas?data=ontem").andExpect(status().isBadRequest());
        GET(gerente, "/relatorios/vendas?data=2026-13-40").andExpect(status().isBadRequest());
        GET(gerente, "/relatorios/vendas?lojaId=" + (loja + 1000)).andExpect(status().isForbidden());
        GET(admin(), "/relatorios/vendas").andExpect(status().isBadRequest());
    }

    @Test
    void vendasDeUmPeriodoEExportacaoCsv() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long pix = pagoComo(gerente, "PIX", 1, 2); // 17,00
        long cancelado = pagoComo(gerente, "DINHEIRO", 2, 1);
        PUT(gerente, "/pedidos/" + cancelado + "/cancelar", "{}").andExpect(status().isOk());
        var hoje = java.time.LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));

        GET(gerente, "/relatorios/vendas?data=" + hoje.minusDays(3) + "&ate=" + hoje).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(hoje.minusDays(3).toString())).andExpect(jsonPath("$.ate").value(hoje.toString()))
                .andExpect(jsonPath("$.pedidos").value(1)).andExpect(jsonPath("$.total").value(17.0));
        GET(gerente, "/relatorios/vendas?data=" + hoje.minusDays(3) + "&ate=" + hoje.minusDays(1))
                .andExpect(jsonPath("$.pedidos").value(0)); // o período termina antes de hoje
        GET(gerente, "/relatorios/vendas").andExpect(jsonPath("$.ate").value(hoje.toString())); // sem "ate": só o dia

        String csv = GET(gerente, "/relatorios/vendas.csv?data=" + hoje + "&ate=" + hoje).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var linhas = csv.split("\n");
        assertEquals("pedido;data;hora;forma;valor", linhas[0]);
        assertEquals(2, linhas.length); // cabeçalho + a venda (o cancelado fica de fora)
        org.junit.jupiter.api.Assertions.assertTrue(linhas[1].startsWith(pix + ";" + hoje + ";") && linhas[1].endsWith(";PIX;17,00"), linhas[1]);

        GET(gerente, "/relatorios/vendas?data=" + hoje + "&ate=" + hoje.minusDays(1)).andExpect(status().isBadRequest());
        GET(gerente, "/relatorios/vendas?data=" + hoje.minusDays(400) + "&ate=" + hoje).andExpect(status().isBadRequest());
        GET(gerente, "/relatorios/vendas.csv?ate=amanha").andExpect(status().isBadRequest());
        GET(token(Papel.ATENDENTE, loja), "/relatorios/vendas.csv").andExpect(status().isForbidden());
    }

    @Test
    void gerenteGeraLinkDeNovaSenhaQueVale1VezEDerrubaASessao() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String email = UUID.randomUUID() + "@exemplo.com";
        long id = Long.parseLong(texto(POST(gerente, "/usuarios", "{\"nome\":\"Bia\",\"email\":\"" + email + "\",\"senha\":\"" + SENHA
                + "\",\"papel\":\"ATENDENTE\"}").andExpect(status().isCreated()), "$.id"));
        String sessaoAntiga = tokenDe(email, SENHA);

        String link = texto(POST(gerente, "/usuarios/" + id + "/redefinicao", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.expiraEm").exists()), "$.token");
        POST(null, "/auth/redefinir-senha", "{\"token\":\"" + link + "\",\"senha\":\"novasenha123\"}").andExpect(status().isNoContent());

        entrar(email, SENHA).andExpect(status().isUnauthorized());           // a senha velha não vale mais
        tokenDe(email, "novasenha123");                                       // a nova vale
        GET(sessaoAntiga, "/produtos").andExpect(status().isUnauthorized());  // sessão aberta caiu
        POST(null, "/auth/redefinir-senha", "{\"token\":\"" + link + "\",\"senha\":\"outrasenha123\"}").andExpect(status().isBadRequest()); // uso único
    }

    @Test
    void linkDeNovaSenhaTemRegras() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String atendente = token(Papel.ATENDENTE, loja);
        String email = UUID.randomUUID() + "@exemplo.com";
        long id = Long.parseLong(texto(POST(gerente, "/usuarios", "{\"nome\":\"Bia\",\"email\":\"" + email + "\",\"senha\":\"" + SENHA
                + "\",\"papel\":\"ATENDENTE\"}").andExpect(status().isCreated()), "$.id"));

        POST(atendente, "/usuarios/" + id + "/redefinicao", "{}").andExpect(status().isForbidden());           // só ADMIN e GERENTE
        POST(token(Papel.GERENTE, novaLoja()), "/usuarios/" + id + "/redefinicao", "{}").andExpect(status().isForbidden()); // outra loja
        POST(gerente, "/usuarios/999999/redefinicao", "{}").andExpect(status().isNotFound());

        String primeiro = texto(POST(gerente, "/usuarios/" + id + "/redefinicao", "{}").andExpect(status().isOk()), "$.token");
        String segundo = texto(POST(admin(), "/usuarios/" + id + "/redefinicao", "{}").andExpect(status().isOk()), "$.token");
        POST(null, "/auth/redefinir-senha", "{\"token\":\"" + primeiro + "\",\"senha\":\"novasenha123\"}").andExpect(status().isBadRequest()); // gerar de novo invalida o anterior
        POST(null, "/auth/redefinir-senha", "{\"token\":\"" + segundo + "\",\"senha\":\"curta\"}").andExpect(status().isBadRequest());           // senha de 8 a 72
        POST(null, "/auth/redefinir-senha", "{\"token\":\"" + segundo + "\",\"senha\":\"novasenha123\"}").andExpect(status().isNoContent());

        PUT(gerente, "/usuarios/" + id + "/ativo", "{\"ativo\":false}").andExpect(status().isOk());
        POST(gerente, "/usuarios/" + id + "/redefinicao", "{}").andExpect(status().isBadRequest());             // conta desativada
    }

}
