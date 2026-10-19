package br.com.lojacafe.pedidos;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// Testa a API de verdade (HTTP + login + validação + banco), sem Kafka e com H2 em memória (não mexe no Postgres).
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:teste",
        "DB_PASSWORD=teste", "KAFKA_USER=teste", "KAFKA_PASSWORD=teste", // sem Kafka nem Postgres de verdade aqui
        // Nos testes o Hibernate cria as tabelas no H2 e o cardápio vem da própria V2 (as migrações são para o Postgres).
        "lojacafe.limite.registros-por-hora=1000", // os testes criam muitas contas do mesmo IP
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.defer-datasource-initialization=true",
        "spring.sql.init.mode=always",
        "spring.sql.init.data-locations=classpath:db/migration/V2__cardapio_inicial.sql"
})
@AutoConfigureMockMvc
class PedidosApiApplicationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    ProdutoRepository produtos;
    @Autowired
    ResultadoPagamentoListener listener;

    // O envio ao Kafka é só um "ok" aqui: não existe broker nos testes.
    @MockitoBean
    PedidoEventos eventos;

    private static final String CONTA = "{\"nome\":\"%s\",\"email\":\"%s\",\"senha\":\"segredo123\"}";

    /** Cria uma conta nova (e-mail único por chamada) e devolve o token. */
    private String novaConta(String nome) throws Exception {
        String corpo = mvc.perform(post("/auth/registrar").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTA.formatted(nome, UUID.randomUUID() + "@exemplo.com")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    private ResultActions postar(String token, String json) throws Exception {
        return mvc.perform(post("/pedidos").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String pedido(String itens) {
        return "{\"formaPagamento\":\"PIX\",\"itens\":" + itens + "}";
    }

    private static String umItem(int produtoId, int quantidade) {
        return pedido("[{\"produtoId\":" + produtoId + ",\"quantidade\":" + quantidade + "}]");
    }

    private int estoque(long id) {
        return produtos.findById(id).orElseThrow().getEstoque();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void cardapioEOpcoesSaoPublicos() throws Exception {
        mvc.perform(get("/produtos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].nome").value("Café coado"))
                .andExpect(jsonPath("$[0].preco").value(8.5))
                .andExpect(jsonPath("$[0].estoque").isNumber());
        mvc.perform(get("/opcoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanhos", hasSize(3)))
                .andExpect(jsonPath("$.extras", hasSize(4)))
                .andExpect(jsonPath("$.formasPagamento", hasSize(4)))
                .andExpect(jsonPath("$.formasPagamento[?(@.codigo=='CREDITO')].parcelasMax", contains(3)));
    }

    // ---------- login ----------

    @Test
    void pedidosExigemLogin() throws Exception {
        mvc.perform(get("/pedidos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content(umItem(1, 1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/pedidos").header("Authorization", "Bearer token-inventado"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrarEEntrar() throws Exception {
        String email = UUID.randomUUID() + "@exemplo.com";
        String conta = CONTA.formatted("Bia", email);
        mvc.perform(post("/auth/registrar").contentType(MediaType.APPLICATION_JSON).content(conta))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Bia"))
                .andExpect(jsonPath("$.token", not(emptyString())));
        // mesmo e-mail (mesmo com outras maiúsculas) não cadastra de novo
        mvc.perform(post("/auth/registrar").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTA.formatted("Outra", email.toUpperCase())))
                .andExpect(status().isConflict());
        mvc.perform(post("/auth/entrar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"segredo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Bia"));
    }

    @Test
    void loginComDadosErradosDa401() throws Exception {
        String email = UUID.randomUUID() + "@exemplo.com";
        mvc.perform(post("/auth/registrar").contentType(MediaType.APPLICATION_JSON).content(CONTA.formatted("Caio", email)))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/entrar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"senha-errada\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/entrar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ninguem@exemplo.com\",\"senha\":\"segredo123\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions entrar(String email, String senha) throws Exception {
        return mvc.perform(post("/auth/entrar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}"));
    }

    private String contaNova(String nome) throws Exception {
        String email = UUID.randomUUID() + "@exemplo.com";
        mvc.perform(post("/auth/registrar").contentType(MediaType.APPLICATION_JSON).content(CONTA.formatted(nome, email)))
                .andExpect(status().isCreated());
        return email;
    }

    @Test
    void senhaErradaVariasVezesBloqueiaMesmoComASenhaCerta() throws Exception {
        String email = contaNova("Duda");
        for (int i = 0; i < 5; i++) {
            entrar(email, "senha-errada").andExpect(status().isUnauthorized());
        }
        // a 6ª tentativa já é barrada, até com a senha certa
        entrar(email, "segredo123").andExpect(status().isTooManyRequests());
        // outro e-mail do mesmo IP não é afetado
        novaConta("Outro");
    }

    @Test
    void loginCertoZeraAsFalhas() throws Exception {
        String email = contaNova("Eva");
        for (int rodada = 0; rodada < 2; rodada++) {
            for (int i = 0; i < 4; i++) {
                entrar(email, "senha-errada").andExpect(status().isUnauthorized());
            }
            // o login certo zera o contador: 4 falhas de novo ainda não bloqueiam
            entrar(email, "segredo123").andExpect(status().isOk());
        }
    }

    @Test
    void cadastroInvalidoDa400() throws Exception {
        for (String conta : new String[]{
                CONTA.formatted("", "a@exemplo.com"),
                CONTA.formatted("Ana", "isto-nao-e-email"),
                "{\"nome\":\"Ana\",\"email\":\"a@exemplo.com\",\"senha\":\"curta\"}"}) {
            mvc.perform(post("/auth/registrar").contentType(MediaType.APPLICATION_JSON).content(conta))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------- pedidos ----------

    @Test
    void postValidoCalculaOValorNoServidor() throws Exception {
        String token = novaConta("Ana");
        // Cappuccino 12,00 + Grande 3,00 + Chantilly 2,00 + Canela 0,50 = 17,50 × 2 = 35,00; mais 1 café coado 8,50
        postar(token, """
                {"formaPagamento":"CREDITO","parcelas":2,"observacao":"sem açúcar",
                 "itens":[{"produtoId":2,"quantidade":2,"tamanho":"GRANDE","extras":["CHANTILLY","CANELA"]},
                          {"produtoId":1,"quantidade":1}]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cliente").value("Ana"))
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
    void precoEnviadoPeloClienteEIgnorado() throws Exception {
        postar(novaConta("Ana"), pedido("[{\"produtoId\":1,\"quantidade\":1,\"precoUnitario\":0.01}]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valor").value(8.5));
    }

    @Test
    void entradasInvalidasDao400() throws Exception {
        String t = novaConta("Ana");
        postar(t, umItem(99, 1)).andExpect(status().isBadRequest());
        postar(t, umItem(1, 0)).andExpect(status().isBadRequest());
        postar(t, umItem(1, 51)).andExpect(status().isBadRequest());
        postar(t, pedido("[]")).andExpect(status().isBadRequest());
        postar(t, "{\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}").andExpect(status().isBadRequest());
        postar(t, "{\"formaPagamento\":\"FIADO\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}").andExpect(status().isBadRequest());
        postar(t, pedido("[{\"produtoId\":1,\"quantidade\":1,\"tamanho\":\"XG\"}]")).andExpect(status().isBadRequest());
        postar(t, pedido("[{\"produtoId\":1,\"quantidade\":1,\"extras\":[\"GELO\"]}]")).andExpect(status().isBadRequest());
        postar(t, "{\"formaPagamento\":\"PIX\",\"observacao\":\"" + "x".repeat(141)
                + "\",\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}").andExpect(status().isBadRequest());
        postar(t, "{\"formaPagamento\":").andExpect(status().isBadRequest());
    }

    @Test
    void parcelasAcimaDoPermitidoDao400() throws Exception {
        String t = novaConta("Ana");
        String item = "[{\"produtoId\":1,\"quantidade\":1}]";
        postar(t, "{\"formaPagamento\":\"PIX\",\"parcelas\":2,\"itens\":" + item + "}").andExpect(status().isBadRequest());
        postar(t, "{\"formaPagamento\":\"CREDITO\",\"parcelas\":3,\"itens\":" + item + "}").andExpect(status().isCreated());
        postar(t, "{\"formaPagamento\":\"DINHEIRO\",\"itens\":" + item + "}").andExpect(status().isCreated());
    }

    @Test
    void erroDeValidacaoDizQualCampoFalhou() throws Exception {
        // O corpo JSON do erro vem do /error, que o MockMvc não percorre: aqui olhamos a exceção.
        // O corpo real (com "errors") foi conferido com a API no ar.
        postar(novaConta("Ana"), pedido("[{\"produtoId\":1,\"quantidade\":0}]"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    var erro = (org.springframework.web.bind.MethodArgumentNotValidException) result.getResolvedException();
                    var campos = erro.getBindingResult().getFieldErrors().stream().map(e -> e.getField()).toList();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            campos.contains("itens[0].quantidade"), "campos: " + campos);
                });
    }

    @Test
    void listaSoMostraOsPedidosDoDonoRespeitandoLimiteEOrdem() throws Exception {
        String ana = novaConta("Ana");
        String bia = novaConta("Bia");
        postar(ana, umItem(1, 1)).andExpect(status().isCreated());
        postar(ana, umItem(2, 1)).andExpect(status().isCreated());
        postar(bia, umItem(1, 1)).andExpect(status().isCreated());
        mvc.perform(get("/pedidos").header("Authorization", "Bearer " + ana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itens[0].item").value("Cappuccino")) // mais novo primeiro
                .andExpect(jsonPath("$[*].cliente", everyItem(is("Ana"))));
        mvc.perform(get("/pedidos").param("limite", "1").header("Authorization", "Bearer " + ana))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- estoque ----------

    @Test
    void estoqueBaixaEFaltaDa400SemGravarNada() throws Exception {
        String t = novaConta("Ana");
        int antes = estoque(4);
        postar(t, umItem(4, 5)).andExpect(status().isCreated());
        assertEquals(antes - 5, estoque(4));
        // pede mais do que existe: 400, o estoque fica como estava e o pedido não é gravado
        postar(t, umItem(4, antes)).andExpect(status().isBadRequest());
        assertEquals(antes - 5, estoque(4));
        mvc.perform(get("/pedidos").header("Authorization", "Bearer " + t)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void faltaDeEstoqueEmUmItemDesfazOsOutros() throws Exception {
        String t = novaConta("Ana");
        int antes = estoque(3);
        postar(t, pedido("[{\"produtoId\":3,\"quantidade\":1},{\"produtoId\":3,\"quantidade\":" + (antes + 1) + "}]"))
                .andExpect(status().isBadRequest());
        assertEquals(antes, estoque(3));
    }

    @Test
    void pagamentoRecusadoDevolveOEstoqueUmaSoVez() throws Exception {
        String t = novaConta("Ana");
        int antes = estoque(3);
        String corpo = postar(t, umItem(3, 10)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        assertEquals(antes - 10, estoque(3));

        String recusado = "{\"versao\":1,\"pedidoId\":" + id
                + ",\"status\":\"RECUSADO\",\"motivo\":\"limite\",\"instancia\":\"caixa-1\"}";
        listener.receber(recusado);
        assertEquals(antes, estoque(3));
        listener.receber(recusado); // evento repetido: não repõe de novo
        assertEquals(antes, estoque(3));
    }

    @Test
    void pagamentoAprovadoNaoMexeNoEstoque() throws Exception {
        String t = novaConta("Ana");
        String corpo = postar(t, umItem(3, 2)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        int depois = estoque(3);
        listener.receber("{\"versao\":1,\"pedidoId\":" + id + ",\"status\":\"APROVADO\",\"motivo\":null,\"instancia\":\"caixa-1\"}");
        assertEquals(depois, estoque(3));
    }
}
