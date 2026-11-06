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

class InsumosTest extends ApiTestBase {

    // ---------- insumos, fichas técnicas e alertas ----------

    private long novoInsumo(String unidade) throws Exception {
        String corpo = "{\"nome\":\"Insumo " + UUID.randomUUID() + "\",\"unidade\":\"" + unidade + "\"}";
        return Long.parseLong(texto(POST(admin(), "/insumos", corpo).andExpect(status().isCreated()), "$.id"));
    }

    private static String linha(long insumoId, String quantidade) {
        return "{\"insumoId\":" + insumoId + ",\"quantidade\":" + quantidade + "}";
    }

    private static String ficha(String... linhas) {
        return "{\"itens\":[" + String.join(",", linhas) + "]}";
    }

    /** Define a ficha de um produto como ADMIN. */
    private void fichaDoProduto(long produtoId, String... linhas) throws Exception {
        PUT(admin(), "/fichas/produtos/" + produtoId, ficha(linhas)).andExpect(status().isOk());
    }

    /** Cria um produto novo com 50 unidades na loja (para não depender dos produtos 1 a 4, usados por outros testes). */
    private long produtoComEstoque(long loja) throws Exception {
        long id = novoProduto("10.00");
        PUT(admin(), "/lojas/" + loja + "/estoque/" + id, "{\"quantidade\":50}").andExpect(status().isOk());
        return id;
    }

    private void contar(String token, long loja, long insumo, String quantidade, String minimo) throws Exception {
        PUT(token, "/lojas/" + loja + "/insumos/" + insumo, "{\"quantidade\":" + quantidade + (minimo == null ? "" : ",\"minimo\":" + minimo) + "}")
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private double insumo(String token, long insumoId, String campo) throws Exception {
        String corpo = GET(token, "/insumos").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((List<Number>) JsonPath.read(corpo, "$[?(@.id==" + insumoId + ")]." + campo)).get(0).doubleValue();
    }

    private ResultActions pedidoDe(String token, long produtoId, int quantidade, String tamanho, String extras) throws Exception {
        return POST(token, "/pedidos", "{\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":" + produtoId + ",\"quantidade\":" + quantidade
                + ",\"tamanho\":\"" + tamanho + "\"" + (extras == null ? "" : ",\"extras\":[" + extras + "]") + "}]}");
    }

    @Test
    void vendaBaixaOsInsumosDaFichaComTamanhoEExtras() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long chantilly = novoInsumo("G");
        long produto = produtoComEstoque(loja);
        fichaDoProduto(produto, linha(leite, "150"));
        PUT(admin(), "/fichas/extras/CHANTILLY", ficha(linha(chantilly, "20"))).andExpect(status().isOk());
        contar(gerente, loja, leite, "5000", "1000");
        contar(gerente, loja, chantilly, "500", null);

        pedidoDe(gerente, produto, 2, "GRANDE", "\"CHANTILLY\"").andExpect(status().isCreated());
        assertEquals(4550.0, insumo(gerente, leite, "quantidade"), 0.0005); // 5000 - 150 × 2 × 1,5
        assertEquals(440.0, insumo(gerente, chantilly, "quantidade"), 0.0005); // 500 - 20 × 2 × 1,5
        pedidoDe(gerente, produto, 1, "PEQUENO", null).andExpect(status().isCreated());
        assertEquals(4437.5, insumo(gerente, leite, "quantidade"), 0.0005); // - 150 × 0,75
    }

    @Test
    void produtoSemFichaNaoMexeNosInsumos() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long produto = produtoComEstoque(loja); // sem ficha
        contar(gerente, loja, leite, "1000", null);
        pedidoDe(gerente, produto, 3, "GRANDE", null).andExpect(status().isCreated());
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005);
    }

    @Test
    void insumoQueAcabaNaoBloqueiaAVendaEViraAlerta() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long produto = produtoComEstoque(loja);
        fichaDoProduto(produto, linha(leite, "150"));
        contar(gerente, loja, leite, "100", "500");

        pedidoDe(gerente, produto, 2, "MEDIO", null).andExpect(status().isCreated()); // gasta 300, só havia 100
        assertEquals(-200.0, insumo(gerente, leite, "quantidade"), 0.0005); // fica negativo, a venda passou
        GET(gerente, "/insumos/alertas").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.insumoId==" + leite + ")].situacao", contains("ACABOU")))
                .andExpect(jsonPath("$[?(@.insumoId==" + leite + ")].quantidade", contains(-200.0)));
    }

    @Test
    void alertasMostramAcabouAntesDeBaixoESoOsAbaixoDoMinimo() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long baixo = novoInsumo("G");
        long acabou = novoInsumo("G");
        long folgado = novoInsumo("G");
        contar(gerente, loja, baixo, "300", "500"); // 300 ≤ 500: baixo
        contar(gerente, loja, acabou, "0", "500"); // 0: acabou
        contar(gerente, loja, folgado, "900", "500"); // 900 > 500: sem alerta

        GET(gerente, "/insumos/alertas").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.insumoId==" + acabou + " || @.insumoId==" + baixo + ")].insumoId", contains((int) acabou, (int) baixo)))
                .andExpect(jsonPath("$[?(@.insumoId==" + folgado + ")]", hasSize(0)));
        GET(gerente, "/insumos").andExpect(jsonPath("$[?(@.id==" + baixo + ")].abaixoDoMinimo", contains(true)))
                .andExpect(jsonPath("$[?(@.id==" + folgado + ")].abaixoDoMinimo", contains(false)));
        GET(admin(), "/insumos/alertas").andExpect(status().isBadRequest()); // o admin precisa dizer a loja
        GET(admin(), "/insumos/alertas?lojaId=" + loja).andExpect(status().isOk());
    }

    @Test
    void pedidoRecusadoDevolveOsInsumosUmaSoVez() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long produto = produtoComEstoque(loja);
        fichaDoProduto(produto, linha(leite, "150"));
        contar(gerente, loja, leite, "1000", null);
        long id = ((Number) JsonPath.read(pedidoDe(gerente, produto, 2, "MEDIO", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals(700.0, insumo(gerente, leite, "quantidade"), 0.0005);

        // a ficha muda depois da venda: a devolução usa o que foi consumido, não a ficha nova
        fichaDoProduto(produto, linha(leite, "400"));
        resultado(id, "RECUSADO");
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005);
        resultado(id, "RECUSADO"); // evento repetido: não devolve de novo
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005);
    }

    @Test
    void cancelarNaFilaDevolveMasCancelarPreparandoEPerda() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long produto = produtoComEstoque(loja);
        fichaDoProduto(produto, linha(leite, "100"));
        contar(gerente, loja, leite, "1000", null);

        // 1) ainda na fila: devolve
        long naFila = ((Number) JsonPath.read(pedidoDe(gerente, produto, 1, "MEDIO", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        resultado(naFila, "APROVADO");
        assertEquals(900.0, insumo(gerente, leite, "quantidade"), 0.0005);
        PUT(gerente, "/pedidos/" + naFila + "/cancelar", "{}").andExpect(status().isOk());
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005);
        PUT(gerente, "/pedidos/" + naFila + "/cancelar", "{}").andExpect(status().isConflict());
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005); // não devolveu de novo

        // 2) já preparando: perda, os insumos não voltam (o estoque de produtos prontos volta)
        long preparando = ((Number) JsonPath.read(pedidoDe(gerente, produto, 1, "MEDIO", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        resultado(preparando, "APROVADO");
        preparo(gerente, preparando, "PREPARANDO").andExpect(status().isOk());
        assertEquals(900.0, insumo(gerente, leite, "quantidade"), 0.0005);
        int produtosAntes = estoque(gerente, (int) produto);
        PUT(gerente, "/pedidos/" + preparando + "/cancelar", "{}").andExpect(status().isOk());
        assertEquals(900.0, insumo(gerente, leite, "quantidade"), 0.0005); // perda: não voltou
        assertEquals(produtosAntes + 1, estoque(gerente, (int) produto)); // o produto pronto voltou
    }

    @Test
    void cancelarAntesDoPagamentoNaoDevolveInsumosEmDobroSeDepoisForRecusado() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long produto = produtoComEstoque(loja);
        fichaDoProduto(produto, linha(leite, "100"));
        contar(gerente, loja, leite, "1000", null);
        long id = ((Number) JsonPath.read(pedidoDe(gerente, produto, 1, "MEDIO", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();

        PUT(gerente, "/pedidos/" + id + "/cancelar", "{}").andExpect(status().isOk()); // ainda aguardando pagamento, na fila
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005);
        resultado(id, "RECUSADO"); // o resultado chega depois
        assertEquals(1000.0, insumo(gerente, leite, "quantidade"), 0.0005); // sem devolução em dobro
    }

    @Test
    void insumoDesativadoNaoConsomeNemAlerta() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long leite = novoInsumo("ML");
        long produto = produtoComEstoque(loja);
        fichaDoProduto(produto, linha(leite, "100"));
        contar(gerente, loja, leite, "0", "500");
        GET(gerente, "/insumos/alertas").andExpect(jsonPath("$[?(@.insumoId==" + leite + ")]", hasSize(1)));

        String nome = texto(GET(gerente, "/insumos"), "$[?(@.id==" + leite + ")].nome").replaceAll("[\\[\\]\"]", "");
        PUT(admin(), "/insumos/" + leite, "{\"nome\":\"" + nome + "\",\"ativo\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
        GET(gerente, "/insumos/alertas").andExpect(jsonPath("$[?(@.insumoId==" + leite + ")]", hasSize(0)));
        pedidoDe(gerente, produto, 1, "MEDIO", null).andExpect(status().isCreated());
        assertEquals(0.0, insumo(gerente, leite, "quantidade"), 0.0005); // desativado: não foi consumido
    }

    @Test
    void lojasNovasEAntigasTemLinhaDeEstoqueDeCadaInsumo() throws Exception {
        long antes = novaLoja();
        long insumo = novoInsumo("UN");
        long depois = novaLoja();
        assertEquals(0.0, insumo(token(Papel.GERENTE, antes), insumo, "quantidade"), 0.0005);
        assertEquals(0.0, insumo(token(Papel.GERENTE, depois), insumo, "quantidade"), 0.0005);
        GET(admin(), "/insumos?lojaId=" + depois).andExpect(jsonPath("$[?(@.id==" + insumo + ")].unidade", contains("UN")));
    }

    @Test
    void insumosTemRegrasDePapel() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        long insumo = novoInsumo("G");
        String gerente = token(Papel.GERENTE, loja);
        String atendente = token(Papel.ATENDENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);
        String corpo = "{\"nome\":\"X " + UUID.randomUUID() + "\",\"unidade\":\"G\"}";

        for (String t : new String[]{atendente, cozinha}) {
            GET(t, "/insumos").andExpect(status().isForbidden());
            GET(t, "/insumos/alertas").andExpect(status().isForbidden());
            GET(t, "/fichas/extras/CHANTILLY").andExpect(status().isForbidden());
            PUT(t, "/lojas/" + loja + "/insumos/" + insumo, "{\"quantidade\":1}").andExpect(status().isForbidden());
        }
        // o gerente enxerga e conta o estoque da própria loja, mas não mexe no catálogo nem nas fichas da rede
        GET(gerente, "/insumos").andExpect(status().isOk());
        GET(gerente, "/fichas/extras/CHANTILLY").andExpect(status().isOk());
        POST(gerente, "/insumos", corpo).andExpect(status().isForbidden());
        PUT(gerente, "/insumos/" + insumo, "{\"nome\":\"Y\"}").andExpect(status().isForbidden());
        PUT(gerente, "/fichas/extras/CHANTILLY", ficha()).andExpect(status().isForbidden());
        PUT(gerente, "/fichas/produtos/1", ficha()).andExpect(status().isForbidden());
        PUT(gerente, "/lojas/" + outra + "/insumos/" + insumo, "{\"quantidade\":1}").andExpect(status().isForbidden()); // outra loja
        GET(gerente, "/insumos?lojaId=" + outra).andExpect(status().isForbidden());
        GET(null, "/insumos").andExpect(status().isUnauthorized());
        PUT(admin(), "/lojas/" + outra + "/insumos/" + insumo, "{\"quantidade\":7}").andExpect(status().isOk()); // o admin conta em qualquer loja
    }

    @Test
    void insumoValidaOsDadosENomeEUnico() throws Exception {
        String t = admin();
        String nome = "Unico " + UUID.randomUUID();
        POST(t, "/insumos", "{\"nome\":\"" + nome + "\",\"unidade\":\"ML\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value(nome)).andExpect(jsonPath("$.unidade").value("ML")).andExpect(jsonPath("$.ativo").value(true));
        POST(t, "/insumos", "{\"nome\":\"" + nome.toUpperCase() + "\",\"unidade\":\"G\"}").andExpect(status().isConflict());
        POST(t, "/insumos", "{\"nome\":\"\",\"unidade\":\"G\"}").andExpect(status().isBadRequest());
        POST(t, "/insumos", "{\"nome\":\"" + "A".repeat(61) + "\",\"unidade\":\"G\"}").andExpect(status().isBadRequest());
        POST(t, "/insumos", "{\"nome\":\"Sem unidade " + UUID.randomUUID() + "\"}").andExpect(status().isBadRequest());
        POST(t, "/insumos", "{\"nome\":\"Unidade ruim " + UUID.randomUUID() + "\",\"unidade\":\"KG\"}").andExpect(status().isBadRequest());
        POST(t, "/insumos", "{\"nome\":").andExpect(status().isBadRequest());
    }

    @Test
    void alterarInsumoTemRegrasEAUnidadeNaoMuda() throws Exception {
        String t = admin();
        long a = novoInsumo("G");
        long b = novoInsumo("ML");
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String nomeA = texto(GET(gerente, "/insumos"), "$[?(@.id==" + a + ")].nome").replaceAll("[\\[\\]\"]", "");
        String nomeB = texto(GET(gerente, "/insumos"), "$[?(@.id==" + b + ")].nome").replaceAll("[\\[\\]\"]", "");

        PUT(t, "/insumos/" + a, "{\"nome\":\"" + nomeB + "\"}").andExpect(status().isConflict()); // nome de outro insumo
        PUT(t, "/insumos/" + a, "{\"nome\":\"" + nomeA.toUpperCase() + "\"}").andExpect(status().isOk()); // o próprio nome, outra caixa
        PUT(t, "/insumos/999999", "{\"nome\":\"Fantasma " + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        PUT(t, "/insumos/" + a, "{\"nome\":\"\"}").andExpect(status().isBadRequest());
        // a unidade é imutável: mandar outra é ignorado
        PUT(t, "/insumos/" + a, "{\"nome\":\"" + nomeA + "\",\"unidade\":\"ML\"}").andExpect(status().isOk()).andExpect(jsonPath("$.unidade").value("G"));
    }

    @Test
    void contagemValidaOsDados() throws Exception {
        long loja = novaLoja();
        long insumo = novoInsumo("G");
        String gerente = token(Papel.GERENTE, loja);
        String base = "/lojas/" + loja + "/insumos/" + insumo;
        PUT(gerente, base, "{\"quantidade\":1200.5,\"minimo\":300}").andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidade").value(1200.5)).andExpect(jsonPath("$.minimo").value(300.0));
        PUT(gerente, base, "{\"quantidade\":900}").andExpect(status().isOk()).andExpect(jsonPath("$.minimo").value(300.0)); // sem minimo: mantém

        PUT(gerente, base, "{}").andExpect(status().isBadRequest());
        PUT(gerente, base, "{\"quantidade\":-1}").andExpect(status().isBadRequest());
        PUT(gerente, base, "{\"quantidade\":1000000000}").andExpect(status().isBadRequest());
        PUT(gerente, base, "{\"quantidade\":1.0001}").andExpect(status().isBadRequest()); // só 3 casas
        PUT(gerente, base, "{\"quantidade\":5,\"minimo\":-1}").andExpect(status().isBadRequest());
        PUT(gerente, base, "{\"quantidade\":\"muito\"}").andExpect(status().isBadRequest());
        PUT(gerente, "/lojas/" + loja + "/insumos/999999", "{\"quantidade\":1}").andExpect(status().isNotFound());
        PUT(admin(), "/lojas/999999/insumos/" + insumo, "{\"quantidade\":1}").andExpect(status().isNotFound());
        assertEquals(900.0, insumo(gerente, insumo, "quantidade"), 0.0005); // nada inválido foi gravado
    }

    @Test
    void fichaSubstituiAAnteriorEPodeSerEsvaziada() throws Exception {
        long a = novoInsumo("ML");
        long b = novoInsumo("G");
        long produto = novoProduto("10.00");
        fichaDoProduto(produto, linha(a, "150"), linha(b, "5.5"));
        GET(admin(), "/fichas/produtos/" + produto).andExpect(status().isOk())
                .andExpect(jsonPath("$.itens", hasSize(2)))
                .andExpect(jsonPath("$.itens[0].insumoId").value((int) a))
                .andExpect(jsonPath("$.itens[0].unidade").value("ML"))
                .andExpect(jsonPath("$.itens[0].quantidade").value(150.0))
                .andExpect(jsonPath("$.itens[1].quantidade").value(5.5));
        fichaDoProduto(produto, linha(b, "2")); // substitui a ficha inteira: o insumo "a" some
        GET(admin(), "/fichas/produtos/" + produto).andExpect(jsonPath("$.itens", hasSize(1))).andExpect(jsonPath("$.itens[0].insumoId").value((int) b));
        fichaDoProduto(produto); // lista vazia: não gasta nada
        GET(admin(), "/fichas/produtos/" + produto).andExpect(jsonPath("$.itens", hasSize(0)));
    }

    @Test
    void fichaValidaOsDados() throws Exception {
        long a = novoInsumo("ML");
        long produto = novoProduto("10.00");
        String t = admin();
        PUT(t, "/fichas/produtos/" + produto, ficha(linha(a, "1"), linha(a, "2"))).andExpect(status().isBadRequest()); // repetido
        PUT(t, "/fichas/produtos/" + produto, ficha(linha(999999, "1"))).andExpect(status().isBadRequest()); // insumo inexistente
        PUT(t, "/fichas/produtos/" + produto, ficha(linha(a, "0"))).andExpect(status().isBadRequest());
        PUT(t, "/fichas/produtos/" + produto, ficha(linha(a, "-1"))).andExpect(status().isBadRequest());
        PUT(t, "/fichas/produtos/" + produto, ficha(linha(a, "100001"))).andExpect(status().isBadRequest());
        PUT(t, "/fichas/produtos/" + produto, ficha(linha(a, "1.0001"))).andExpect(status().isBadRequest());
        PUT(t, "/fichas/produtos/" + produto, "{}").andExpect(status().isBadRequest()); // sem a lista
        String muitos = String.join(",", java.util.Collections.nCopies(31, linha(a, "1")));
        PUT(t, "/fichas/produtos/" + produto, "{\"itens\":[" + muitos + "]}").andExpect(status().isBadRequest()); // mais de 30
        PUT(t, "/fichas/produtos/999999", ficha()).andExpect(status().isNotFound());
        GET(t, "/fichas/produtos/999999").andExpect(status().isNotFound());
        GET(t, "/fichas/extras/GELO").andExpect(status().isBadRequest()); // extra que não existe
        PUT(t, "/fichas/extras/GELO", ficha()).andExpect(status().isBadRequest());
        GET(t, "/fichas/produtos/" + produto).andExpect(jsonPath("$.itens", hasSize(0))); // nada inválido foi gravado
    }

    @Test
    void tamanhosInformamOFatorDeInsumo() throws Exception {
        GET(null, "/opcoes").andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanhos[?(@.codigo=='PEQUENO')].fatorInsumo", contains(0.75)))
                .andExpect(jsonPath("$.tamanhos[?(@.codigo=='MEDIO')].fatorInsumo", contains(1.0)))
                .andExpect(jsonPath("$.tamanhos[?(@.codigo=='GRANDE')].fatorInsumo", contains(1.5)));
    }

    @Test
    void insumosFichasEContagemVaoParaAAuditoria() throws Exception {
        long loja = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        long insumo = novoInsumo("ML");
        long produto = novoProduto("10.00");
        fichaDoProduto(produto, linha(insumo, "100"));
        contar(gerente, loja, insumo, "800", "200");

        GET(admin(), "/auditoria?limite=500")
                .andExpect(jsonPath("$[?(@.acao=='INSUMO_CRIADO' && @.entidadeId=='" + insumo + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.acao=='FICHA_ALTERADA' && @.entidade=='Produto' && @.entidadeId=='" + produto + "')].detalhe", contains("1 insumo(s)")));
        // a contagem é da loja: o gerente a vê, com antes e depois
        GET(gerente, "/auditoria").andExpect(jsonPath("$[?(@.acao=='INSUMO_AJUSTADO')].detalhe",
                contains(allOf(containsString("0.000 -> 800"), containsString("mínimo 0.000 -> 200")))));
        // o catálogo e as fichas são da rede (sem loja): o gerente não os vê na auditoria
        GET(gerente, "/auditoria?limite=500").andExpect(jsonPath("$[?(@.acao=='INSUMO_CRIADO' || @.acao=='FICHA_ALTERADA')]", hasSize(0)));
    }

}
