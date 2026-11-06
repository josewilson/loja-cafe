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

class CardapioRedeTest extends ApiTestBase {

    // ---------- cardápio da rede (ADMIN) ----------

    private static String dadosProduto(String nome, String preco, boolean ativo) {
        return "{\"nome\":\"" + nome + "\",\"descricao\":\"d\",\"emoji\":\"🥐\",\"preco\":" + preco + ",\"ativo\":" + ativo + "}";
    }

    @Test
    void adminCriaProdutoQueNasceSemEstoqueEmTodasAsLojas() throws Exception {
        long loja = novaLoja();
        String atendente = token(Papel.ATENDENTE, loja);
        String nome = "Pão na chapa " + UUID.randomUUID();
        var r = POST(admin(), "/cardapio", "{\"nome\":\"" + nome + "\",\"descricao\":\"Com manteiga\",\"emoji\":\"🥖\",\"preco\":7.00}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", greaterThanOrEqualTo(100))) // ids novos vêm do banco
                .andExpect(jsonPath("$.nome").value(nome))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.preco").value(7.0));
        long id = Long.parseLong(texto(r, "$.id"));

        // aparece no balcão da loja já existente, com estoque 0
        GET(atendente, "/produtos").andExpect(jsonPath("$[?(@.id==" + id + ")].estoque", contains(0)));
        POST(atendente, "/pedidos", umItem((int) id, 1)).andExpect(status().isBadRequest()); // sem estoque
        PUT(admin(), "/lojas/" + loja + "/estoque/" + id, "{\"quantidade\":5}").andExpect(status().isOk());
        POST(atendente, "/pedidos", umItem((int) id, 2)).andExpect(status().isCreated()).andExpect(jsonPath("$.valor").value(14.0));

        // e uma loja criada depois também tem a linha de estoque do produto
        long outra = novaLoja();
        GET(token(Papel.ATENDENTE, outra), "/produtos").andExpect(jsonPath("$[?(@.id==" + id + ")].estoque", contains(50)));
    }

    @Test
    void precoNovoValeSoParaPedidosNovos() throws Exception {
        long id = novoProduto("10.00");
        long loja = novaLoja();
        PUT(admin(), "/lojas/" + loja + "/estoque/" + id, "{\"quantidade\":10}").andExpect(status().isOk());
        String atendente = token(Papel.ATENDENTE, loja);

        POST(atendente, "/pedidos", umItem((int) id, 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.valor").value(10.0));
        PUT(admin(), "/cardapio/" + id, dadosProduto("Produto reajustado " + UUID.randomUUID(), "13.50", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preco").value(13.5));
        POST(atendente, "/pedidos", umItem((int) id, 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.valor").value(13.5));

        // o pedido antigo continua com o preço que usou
        GET(atendente, "/pedidos").andExpect(jsonPath("$[1].valor").value(10.0)).andExpect(jsonPath("$[0].valor").value(13.5));
        GET(atendente, "/produtos").andExpect(jsonPath("$[?(@.id==" + id + ")].preco", contains(13.5)));
    }

    @Test
    void produtoDesativadoSomeDoBalcaoENaoAceitaPedidoMasFicaNoCardapioDoAdmin() throws Exception {
        long id = novoProduto("5.00");
        long loja = novaLoja();
        PUT(admin(), "/lojas/" + loja + "/estoque/" + id, "{\"quantidade\":10}").andExpect(status().isOk());
        String atendente = token(Papel.ATENDENTE, loja);
        long pedido = Long.parseLong(texto(POST(atendente, "/pedidos", umItem((int) id, 1)).andExpect(status().isCreated()), "$.id"));
        String nome = texto(GET(admin(), "/cardapio"), "$[?(@.id==" + id + ")].nome").replaceAll("[\\[\\]\"]", "");

        PUT(admin(), "/cardapio/" + id, dadosProduto(nome, "5.00", false)).andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
        GET(atendente, "/produtos").andExpect(jsonPath("$[?(@.id==" + id + ")]", hasSize(0)));
        POST(atendente, "/pedidos", umItem((int) id, 1)).andExpect(status().isBadRequest());
        GET(admin(), "/cardapio").andExpect(jsonPath("$[?(@.id==" + id + ")].ativo", contains(false)));
        // o pedido antigo e o histórico continuam intactos
        GET(atendente, "/pedidos").andExpect(jsonPath("$[0].id").value(pedido)).andExpect(jsonPath("$[0].itens[0].item").value(nome));

        PUT(admin(), "/cardapio/" + id, dadosProduto(nome, "5.00", true)).andExpect(status().isOk());
        GET(atendente, "/produtos").andExpect(jsonPath("$[?(@.id==" + id + ")]", hasSize(1)));
        POST(atendente, "/pedidos", umItem((int) id, 1)).andExpect(status().isCreated());
    }

    @Test
    void ativoOmitidoNoPutMantemOEstadoAtual() throws Exception {
        long id = novoProduto("5.00");
        String nome = texto(GET(admin(), "/cardapio"), "$[?(@.id==" + id + ")].nome").replaceAll("[\\[\\]\"]", "");
        PUT(admin(), "/cardapio/" + id, dadosProduto(nome, "5.00", false)).andExpect(status().isOk());
        PUT(admin(), "/cardapio/" + id, "{\"nome\":\"" + nome + "\",\"emoji\":\"🥐\",\"preco\":6.00}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false)).andExpect(jsonPath("$.preco").value(6.0));
    }

    @Test
    void soOAdminEditaOCardapio() throws Exception {
        long loja = novaLoja();
        long id = novoProduto("5.00");
        String dados = dadosProduto("Qualquer " + UUID.randomUUID(), "5.00", true);
        for (String t : new String[]{token(Papel.GERENTE, loja), token(Papel.ATENDENTE, loja), token(Papel.COZINHA, loja)}) {
            GET(t, "/cardapio").andExpect(status().isForbidden());
            POST(t, "/cardapio", dados).andExpect(status().isForbidden());
            PUT(t, "/cardapio/" + id, dados).andExpect(status().isForbidden());
        }
        GET(null, "/cardapio").andExpect(status().isUnauthorized());
        POST(null, "/cardapio", dados).andExpect(status().isUnauthorized());
    }

    @Test
    void produtoValidaOsDadosENomeEUnico() throws Exception {
        String t = admin();
        String nome = "Unico " + UUID.randomUUID();
        POST(t, "/cardapio", dadosProduto(nome, "5.00", true)).andExpect(status().isCreated());
        POST(t, "/cardapio", dadosProduto(nome.toUpperCase(), "5.00", true)).andExpect(status().isConflict()); // sem diferenciar maiúsculas

        POST(t, "/cardapio", dadosProduto("", "5.00", true)).andExpect(status().isBadRequest());
        POST(t, "/cardapio", dadosProduto("A".repeat(61), "5.00", true)).andExpect(status().isBadRequest());
        POST(t, "/cardapio", dadosProduto("Zero " + UUID.randomUUID(), "0", true)).andExpect(status().isBadRequest());
        POST(t, "/cardapio", dadosProduto("Neg " + UUID.randomUUID(), "-1", true)).andExpect(status().isBadRequest());
        POST(t, "/cardapio", dadosProduto("Caro " + UUID.randomUUID(), "10000.01", true)).andExpect(status().isBadRequest());
        POST(t, "/cardapio", dadosProduto("Casas " + UUID.randomUUID(), "5.001", true)).andExpect(status().isBadRequest());
        POST(t, "/cardapio", "{\"nome\":\"Sem emoji " + UUID.randomUUID() + "\",\"preco\":5.00}").andExpect(status().isBadRequest());
        POST(t, "/cardapio", "{\"nome\":\"Sem preco " + UUID.randomUUID() + "\",\"emoji\":\"🥐\"}").andExpect(status().isBadRequest());
        POST(t, "/cardapio", "{\"nome\":\"Desc " + UUID.randomUUID() + "\",\"descricao\":\"" + "x".repeat(141) + "\",\"emoji\":\"🥐\",\"preco\":5}")
                .andExpect(status().isBadRequest());
        POST(t, "/cardapio", "{\"nome\":\"Emoji " + UUID.randomUUID() + "\",\"emoji\":\"" + "e".repeat(17) + "\",\"preco\":5}").andExpect(status().isBadRequest());
        POST(t, "/cardapio", "{\"nome\":").andExpect(status().isBadRequest());
    }

    @Test
    void alterarTemRegras() throws Exception {
        String t = admin();
        long a = novoProduto("5.00");
        long b = novoProduto("6.00");
        String nomeA = texto(GET(t, "/cardapio"), "$[?(@.id==" + a + ")].nome").replaceAll("[\\[\\]\"]", "");
        String nomeB = texto(GET(t, "/cardapio"), "$[?(@.id==" + b + ")].nome").replaceAll("[\\[\\]\"]", "");
        PUT(t, "/cardapio/" + a, dadosProduto(nomeB, "5.00", true)).andExpect(status().isConflict()); // nome de outro produto
        PUT(t, "/cardapio/" + a, dadosProduto(nomeA.toUpperCase(), "5.00", true)).andExpect(status().isOk()); // o próprio nome, outra caixa
        PUT(t, "/cardapio/999999", dadosProduto("Fantasma " + UUID.randomUUID(), "5.00", true)).andExpect(status().isNotFound());
        PUT(t, "/cardapio/" + a, dadosProduto(nomeA, "0", true)).andExpect(status().isBadRequest());
    }

    @Test
    void cardapioVaiParaAAuditoriaSoDoAdmin() throws Exception {
        long id = novoProduto("8.00");
        String nome = texto(GET(admin(), "/cardapio"), "$[?(@.id==" + id + ")].nome").replaceAll("[\\[\\]\"]", "");
        PUT(admin(), "/cardapio/" + id, dadosProduto(nome, "9.50", false)).andExpect(status().isOk());
        PUT(admin(), "/cardapio/" + id, dadosProduto(nome, "9.50", false)).andExpect(status().isOk()); // sem mudança: não grava de novo

        GET(admin(), "/auditoria?limite=500")
                .andExpect(jsonPath("$[?(@.acao=='PRODUTO_CRIADO' && @.entidadeId=='" + id + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.acao=='PRODUTO_ALTERADO' && @.entidadeId=='" + id + "')].detalhe",
                        contains(allOf(containsString("preço 8.00 -> 9.50"), containsString("ativo true -> false")))));
        // o gerente só vê a própria loja: mudanças da rede (sem loja) não aparecem para ele
        GET(token(Papel.GERENTE, novaLoja()), "/auditoria?limite=500")
                .andExpect(jsonPath("$[?(@.acao=='PRODUTO_ALTERADO')]", hasSize(0)));
    }

}
