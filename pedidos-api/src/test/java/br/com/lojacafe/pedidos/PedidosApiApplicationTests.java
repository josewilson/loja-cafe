package br.com.lojacafe.pedidos;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

// Testa a API de verdade (HTTP + login + papéis + validação + banco), sem Kafka e com H2 em memória (não mexe no Postgres).
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:teste",
        "DB_PASSWORD=teste", "KAFKA_USER=teste", "KAFKA_PASSWORD=teste", // sem Kafka nem Postgres de verdade aqui
        "lojacafe.admin.email=admin@teste.com", "lojacafe.admin.senha=admin12345",
        "lojacafe.bcrypt-custo=4", // BCrypt no custo mínimo: nos testes a velocidade importa mais que a força (em produção o padrão é 10)
        // Nos testes o Hibernate cria as tabelas no H2 e o cardápio vem de seed-teste.sql (as migrações são para o Postgres).
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.defer-datasource-initialization=true",
        "spring.sql.init.mode=always",
        "spring.sql.init.data-locations=classpath:seed-teste.sql"
})
@AutoConfigureMockMvc
class PedidosApiApplicationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    PagamentosKafka listener;

    // O envio ao Kafka é só um "ok" aqui: não existe broker nos testes.
    @MockitoSpyBean
    PagamentosKafka eventos;

    @org.junit.jupiter.api.BeforeEach
    void semKafka() throws Exception {
        org.mockito.Mockito.doNothing().when(eventos).publicarCriado(org.mockito.ArgumentMatchers.any());
    }

    // ---------- ajudantes ----------

    private ResultActions GET(String token, String caminho) throws Exception {
        var req = get(caminho);
        return mvc.perform(token == null ? req : req.header("Authorization", "Bearer " + token));
    }

    private ResultActions enviar(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                                 String token, String json) throws Exception {
        req = req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(token == null ? req : req.header("Authorization", "Bearer " + token));
    }

    private ResultActions POST(String token, String caminho, String json) throws Exception {
        return enviar(post(caminho), token, json);
    }

    private ResultActions PUT(String token, String caminho, String json) throws Exception {
        return enviar(put(caminho), token, json);
    }

    private ResultActions entrar(String email, String senha) throws Exception {
        return POST(null, "/auth/entrar", "{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}");
    }

    private static String texto(ResultActions r, String caminho) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), caminho).toString();
    }

    private String tokenDe(String email, String senha) throws Exception {
        return texto(entrar(email, senha).andExpect(status().isOk()), "$.token");
    }

    // O login gasta BCrypt (devagar de propósito): o token do admin é pedido uma vez só.
    private static String tokenAdmin;

    private String admin() throws Exception {
        if (tokenAdmin == null) {
            tokenAdmin = tokenDe("admin@teste.com", "admin12345");
        }
        return tokenAdmin;
    }

    /** Cria uma loja nova (50 unidades de cada produto) e devolve o id. */
    private long novaLoja() throws Exception {
        var r = POST(admin(), "/lojas", "{\"nome\":\"Loja " + UUID.randomUUID() + "\",\"estoqueInicial\":50}").andExpect(status().isCreated());
        return Long.parseLong(texto(r, "$.id"));
    }

    private static final String SENHA = "segredo123";

    /** Cria um usuário com o papel na loja (e-mail único) e devolve o e-mail. */
    private String novoUsuario(Papel papel, Long lojaId) throws Exception {
        String email = UUID.randomUUID() + "@exemplo.com";
        String loja = lojaId == null ? "" : ",\"lojaId\":" + lojaId;
        POST(admin(), "/usuarios", "{\"nome\":\"" + papel + "\",\"email\":\"" + email + "\",\"senha\":\"" + SENHA
                + "\",\"papel\":\"" + papel + "\"" + loja + "}").andExpect(status().isCreated());
        return email;
    }

    private String token(Papel papel, long lojaId) throws Exception {
        return tokenDe(novoUsuario(papel, lojaId), SENHA);
    }

    private static String pedido(String itens) {
        return "{\"formaPagamento\":\"PIX\",\"itens\":" + itens + "}";
    }

    private static String umItem(int produtoId, int quantidade) {
        return pedido("[{\"produtoId\":" + produtoId + ",\"quantidade\":" + quantidade + "}]");
    }

    @SuppressWarnings("unchecked")
    private int estoque(String token, int produtoId) throws Exception {
        String corpo = GET(token, "/produtos").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((List<Integer>) JsonPath.read(corpo, "$[?(@.id==" + produtoId + ")].estoque")).get(0);
    }

    // ---------- básico ----------

    @Test
    void contextLoads() {
    }

    @Test
    void opcoesSaoPublicas() throws Exception {
        GET(null, "/opcoes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanhos", hasSize(3)))
                .andExpect(jsonPath("$.extras", hasSize(4)))
                .andExpect(jsonPath("$.formasPagamento", hasSize(4)))
                .andExpect(jsonPath("$.formasPagamento[?(@.codigo=='CREDITO')].parcelasMax", contains(3)));
    }

    @Test
    void todoOResto_exigeLogin() throws Exception {
        for (String caminho : new String[]{"/produtos", "/pedidos", "/usuarios", "/lojas", "/auditoria"}) {
            GET(null, caminho).andExpect(status().isUnauthorized());
        }
        POST(null, "/pedidos", umItem(1, 1)).andExpect(status().isUnauthorized());
        POST(null, "/auth/sair", "{}").andExpect(status().isUnauthorized());
        GET("token-inventado", "/pedidos").andExpect(status().isUnauthorized());
    }

    // ---------- login ----------

    @Test
    void loginDevolvePapelELoja() throws Exception {
        entrar("admin@teste.com", "admin12345").andExpect(status().isOk())
                .andExpect(jsonPath("$.papel").value("ADMIN"))
                .andExpect(jsonPath("$.lojaId").doesNotExist());
        long loja = novaLoja();
        entrar(novoUsuario(Papel.ATENDENTE, loja), SENHA).andExpect(status().isOk())
                .andExpect(jsonPath("$.papel").value("ATENDENTE"))
                .andExpect(jsonPath("$.lojaId").value(loja))
                .andExpect(jsonPath("$.lojaNome", startsWith("Loja ")))
                .andExpect(jsonPath("$.token", not(emptyString())));
    }

    @Test
    void loginComDadosErradosDa401() throws Exception {
        String email = novoUsuario(Papel.ATENDENTE, novaLoja());
        entrar(email, "senha-errada").andExpect(status().isUnauthorized());
        entrar("ninguem@exemplo.com", SENHA).andExpect(status().isUnauthorized());
        entrar(email.toUpperCase(), SENHA).andExpect(status().isOk()); // e-mail não diferencia maiúsculas
    }

    @Test
    void senhaErradaVariasVezesBloqueiaMesmoComASenhaCerta() throws Exception {
        String email = novoUsuario(Papel.ATENDENTE, novaLoja());
        for (int i = 0; i < 5; i++) {
            entrar(email, "senha-errada").andExpect(status().isUnauthorized());
        }
        entrar(email, SENHA).andExpect(status().isTooManyRequests()); // a 6ª já é barrada, até com a senha certa
        entrar(novoUsuario(Papel.ATENDENTE, novaLoja()), SENHA).andExpect(status().isOk()); // outro e-mail não é afetado
    }

    @Test
    void loginCertoZeraAsFalhas() throws Exception {
        String email = novoUsuario(Papel.ATENDENTE, novaLoja());
        for (int rodada = 0; rodada < 2; rodada++) {
            for (int i = 0; i < 4; i++) {
                entrar(email, "senha-errada").andExpect(status().isUnauthorized());
            }
            entrar(email, SENHA).andExpect(status().isOk()); // zera o contador: 4 falhas de novo ainda não bloqueiam
        }
    }

    @Test
    void sairEncerraASessao() throws Exception {
        String t = token(Papel.ATENDENTE, novaLoja());
        GET(t, "/pedidos").andExpect(status().isOk());
        POST(t, "/auth/sair", "{}").andExpect(status().isNoContent());
        GET(t, "/pedidos").andExpect(status().isUnauthorized());
    }

    // ---------- papéis ----------

    @Test
    void cadaPapelSoFazOQuePode() throws Exception {
        long loja = novaLoja();
        String atendente = token(Papel.ATENDENTE, loja);
        String cozinha = token(Papel.COZINHA, loja);
        String gerente = token(Papel.GERENTE, loja);

        // atendente: lança e vê pedidos, mas não administra nada
        POST(atendente, "/pedidos", umItem(1, 1)).andExpect(status().isCreated());
        GET(atendente, "/pedidos").andExpect(status().isOk());
        GET(atendente, "/usuarios").andExpect(status().isForbidden());
        GET(atendente, "/auditoria").andExpect(status().isForbidden());
        GET(atendente, "/lojas").andExpect(status().isForbidden());
        PUT(atendente, "/lojas/" + loja + "/estoque/1", "{\"quantidade\":5}").andExpect(status().isForbidden());

        // cozinha: só enxerga
        GET(cozinha, "/pedidos").andExpect(status().isOk());
        GET(cozinha, "/produtos").andExpect(status().isOk());
        POST(cozinha, "/pedidos", umItem(1, 1)).andExpect(status().isForbidden());

        // gerente: lança pedido e administra a própria loja, mas não cria lojas
        POST(gerente, "/pedidos", umItem(1, 1)).andExpect(status().isCreated());
        GET(gerente, "/usuarios").andExpect(status().isOk());
        GET(gerente, "/auditoria").andExpect(status().isOk());
        POST(gerente, "/lojas", "{\"nome\":\"X\"}").andExpect(status().isForbidden());
        GET(gerente, "/lojas").andExpect(status().isForbidden());

        // admin não tem loja, então não lança pedido
        POST(admin(), "/pedidos", umItem(1, 1)).andExpect(status().isForbidden());
    }

    @Test
    void gerenteCriaSoAtendenteECozinhaNaPropriaLoja() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String base = "\"nome\":\"Novo\",\"senha\":\"" + SENHA + "\"";

        POST(gerente, "/usuarios", "{" + base + ",\"email\":\"" + UUID.randomUUID() + "@x.com\",\"papel\":\"ATENDENTE\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lojaId").value(loja));
        POST(gerente, "/usuarios", "{" + base + ",\"email\":\"" + UUID.randomUUID() + "@x.com\",\"papel\":\"GERENTE\"}")
                .andExpect(status().isForbidden());
        POST(gerente, "/usuarios", "{" + base + ",\"email\":\"" + UUID.randomUUID() + "@x.com\",\"papel\":\"ADMIN\"}")
                .andExpect(status().isForbidden());
        POST(gerente, "/usuarios", "{" + base + ",\"email\":\"" + UUID.randomUUID() + "@x.com\",\"papel\":\"COZINHA\",\"lojaId\":" + outra + "}")
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCriaUsuariosEValidaOsDados() throws Exception {
        long loja = novaLoja();
        String t = admin();
        String email = UUID.randomUUID() + "@x.com";
        String corpo = "{\"nome\":\"Bia\",\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\",\"papel\":\"ATENDENTE\",\"lojaId\":" + loja + "}";
        POST(t, "/usuarios", corpo).andExpect(status().isCreated())
                .andExpect(jsonPath("$.senha").doesNotExist())
                .andExpect(jsonPath("$.senhaHash").doesNotExist())
                .andExpect(jsonPath("$.ativo").value(true));
        POST(t, "/usuarios", corpo.replace(email, email.toUpperCase())).andExpect(status().isConflict());
        // papel de loja exige lojaId existente; ADMIN não tem loja
        POST(t, "/usuarios", corpo.replace(",\"lojaId\":" + loja, "")).andExpect(status().isBadRequest());
        POST(t, "/usuarios", corpo.replace("\"lojaId\":" + loja, "\"lojaId\":999999")).andExpect(status().isBadRequest());
        POST(t, "/usuarios", "{\"nome\":\"Chefe\",\"email\":\"" + UUID.randomUUID() + "@x.com\",\"senha\":\"" + SENHA + "\",\"papel\":\"ADMIN\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lojaId").doesNotExist());
        // dados inválidos
        POST(t, "/usuarios", "{\"nome\":\"\",\"email\":\"nao-e-email\",\"senha\":\"curta\",\"papel\":\"ATENDENTE\"}")
                .andExpect(status().isBadRequest());
        POST(t, "/usuarios", "{\"nome\":\"X\",\"email\":\"a@b.com\",\"senha\":\"" + SENHA + "\",\"papel\":\"CHEFE\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void desativarUsuarioDerrubaASessaoEBloqueiaOLogin() throws Exception {
        long loja = novaLoja();
        String email = novoUsuario(Papel.ATENDENTE, loja);
        String t = tokenDe(email, SENHA);
        long id = Long.parseLong(texto(GET(admin(), "/usuarios"), "$[?(@.email=='" + email + "')].id").replaceAll("[\\[\\]]", ""));
        GET(t, "/pedidos").andExpect(status().isOk());

        PUT(admin(), "/usuarios/" + id + "/ativo", "{\"ativo\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
        GET(t, "/pedidos").andExpect(status().isUnauthorized());
        entrar(email, SENHA).andExpect(status().isUnauthorized());

        PUT(admin(), "/usuarios/" + id + "/ativo", "{\"ativo\":true}").andExpect(status().isOk());
        entrar(email, SENHA).andExpect(status().isOk());
    }

    @Test
    void desativarTemRegras() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        String emailOutra = novoUsuario(Papel.ATENDENTE, outra);
        String emailGerente2 = novoUsuario(Papel.GERENTE, loja);
        long idOutra = Long.parseLong(texto(GET(admin(), "/usuarios"), "$[?(@.email=='" + emailOutra + "')].id").replaceAll("[\\[\\]]", ""));
        long idGerente2 = Long.parseLong(texto(GET(admin(), "/usuarios"), "$[?(@.email=='" + emailGerente2 + "')].id").replaceAll("[\\[\\]]", ""));
        long idAdmin = Long.parseLong(texto(GET(admin(), "/usuarios"), "$[?(@.email=='admin@teste.com')].id").replaceAll("[\\[\\]]", ""));

        PUT(gerente, "/usuarios/" + idOutra + "/ativo", "{\"ativo\":false}").andExpect(status().isForbidden()); // outra loja
        PUT(gerente, "/usuarios/" + idGerente2 + "/ativo", "{\"ativo\":false}").andExpect(status().isForbidden()); // outro gerente
        PUT(admin(), "/usuarios/" + idAdmin + "/ativo", "{\"ativo\":false}").andExpect(status().isBadRequest()); // a si mesmo
        PUT(admin(), "/usuarios/999999/ativo", "{\"ativo\":false}").andExpect(status().isNotFound());
    }

    @Test
    void listaDeUsuariosRespeitaALoja() throws Exception {
        long loja = novaLoja();
        long outra = novaLoja();
        String gerente = token(Papel.GERENTE, loja);
        novoUsuario(Papel.ATENDENTE, loja);
        novoUsuario(Papel.ATENDENTE, outra);
        GET(gerente, "/usuarios").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].lojaId", everyItem(is((int) loja))))
                .andExpect(jsonPath("$", hasSize(2))); // o gerente e o atendente da loja dele
        GET(admin(), "/usuarios").andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))));
    }

    // ---------- lojas ----------

    @Test
    void adminCriaLojaComEstoqueInicial() throws Exception {
        String t = admin();
        String nome = "Loja " + UUID.randomUUID();
        POST(t, "/lojas", "{\"nome\":\"" + nome + "\",\"estoqueInicial\":7}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value(nome)).andExpect(jsonPath("$.ativa").value(true));
        POST(t, "/lojas", "{\"nome\":\"" + nome + "\"}").andExpect(status().isConflict());
        POST(t, "/lojas", "{\"nome\":\"\"}").andExpect(status().isBadRequest());
        POST(t, "/lojas", "{\"nome\":\"Y\",\"estoqueInicial\":-1}").andExpect(status().isBadRequest());
        GET(t, "/lojas").andExpect(status().isOk()).andExpect(jsonPath("$[?(@.nome=='" + nome + "')]", hasSize(1)));
        // sem estoqueInicial a loja nasce zerada
        long zerada = Long.parseLong(texto(POST(t, "/lojas", "{\"nome\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isCreated()), "$.id"));
        assertEquals(0, estoque(token(Papel.ATENDENTE, zerada), 1));
    }

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

    /** Lança um pedido (produto, quantidade) como esse atendente e devolve o id. O pagamento fica aguardando. */
    private long lancar(String token, int produtoId, int quantidade) throws Exception {
        String corpo = POST(token, "/pedidos", umItem(produtoId, quantidade)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    private void resultado(long pedidoId, String status) {
        listener.receber("{\"versao\":1,\"pedidoId\":" + pedidoId + ",\"status\":\"" + status
                + "\",\"motivo\":null,\"instancia\":\"caixa-1\"}");
    }

    /** Lança e já deixa o pedido PAGO. */
    private long lancarPago(String token, int produtoId, int quantidade) throws Exception {
        long id = lancar(token, produtoId, quantidade);
        resultado(id, "APROVADO");
        return id;
    }

    private ResultActions preparo(String token, long id, String novo) throws Exception {
        return PUT(token, "/pedidos/" + id + "/preparo", "{\"preparo\":\"" + novo + "\"}");
    }

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

    // ---------- painel do gerente: caixa e vendas ----------

    /** Lança um pedido com essa forma de pagamento e já o deixa PAGO. Preços: produto 1 = 8,50, 2 = 12,00, 3 = 6,00, 4 = 9,50. */
    private long pagoComo(String token, String forma, int produtoId, int quantidade) throws Exception {
        String corpo = POST(token, "/pedidos", "{\"formaPagamento\":\"" + forma + "\",\"itens\":[{\"produtoId\":" + produtoId
                + ",\"quantidade\":" + quantidade + "}]}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        resultado(id, "APROVADO");
        return id;
    }

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
        PUT(gerente, "/pedidos/" + pagoComo(gerente, "PIX", 3, 1) + "/cancelar", "{}").andExpect(status().isOk());

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
                .andExpect(jsonPath("$.aguardando").value(1));
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

    // ---------- cardápio da rede (ADMIN) ----------

    /** Cria um produto novo (nome único) e devolve o id. */
    private long novoProduto(String preco) throws Exception {
        String corpo = "{\"nome\":\"Produto " + UUID.randomUUID() + "\",\"descricao\":\"teste\",\"emoji\":\"🥐\",\"preco\":" + preco + "}";
        return Long.parseLong(texto(POST(admin(), "/cardapio", corpo).andExpect(status().isCreated()), "$.id"));
    }

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

    // ---------- saúde da API (monitoramento e Docker) ----------

    @MockitoSpyBean
    SaudeController saude;

    @Test
    void saudeEPublicaEComTudoVivoDa200() throws Exception {
        org.mockito.Mockito.doReturn(true).when(saude).kafkaVivo(); // nos testes não há broker de verdade
        GET(null, "/saude").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.banco").value("ok"))
                .andExpect(jsonPath("$.kafka").value("ok"));
    }

    @Test
    void saudeSemKafkaDa503ComOBancoOk() throws Exception {
        org.mockito.Mockito.doReturn(false).when(saude).kafkaVivo();
        GET(null, "/saude").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("falha"))
                .andExpect(jsonPath("$.banco").value("ok"))
                .andExpect(jsonPath("$.kafka").value("falha"));
    }

    @Test
    void saudeSemBancoDa503() throws Exception {
        org.mockito.Mockito.doReturn(false).when(saude).bancoVivo();
        org.mockito.Mockito.doReturn(true).when(saude).kafkaVivo();
        GET(null, "/saude").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.banco").value("falha"))
                .andExpect(jsonPath("$.kafka").value("ok"));
    }

    @Test
    void oBancoRealDosTestesResponde() {
        org.junit.jupiter.api.Assertions.assertTrue(saude.bancoVivo()); // consulta de verdade ao H2
    }

    @Test
    void saudeNaoVazaDetalhesInternos() throws Exception {
        org.mockito.Mockito.doReturn(false).when(saude).kafkaVivo();
        String corpo = GET(null, "/saude").andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(corpo.contains("jdbc") || corpo.contains("localhost") || corpo.contains("Exception"), corpo);
    }

    // ---------- reenviar pedidos parados ao Pagamentos (depois de restaurar um backup) ----------

    private java.util.Set<Long> idsReenviados() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(Pedido.class);
        org.mockito.Mockito.verify(eventos, org.mockito.Mockito.atLeast(0)).publicarCriado(captor.capture());
        return captor.getAllValues().stream().map(Pedido::getId).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void reenfileirarReenviaSoOsPedidosQueAindaAguardam() throws Exception {
        String atendente = token(Papel.ATENDENTE, novaLoja());
        long parado1 = lancar(atendente, 1, 1);
        long parado2 = lancar(atendente, 1, 1);
        long pago = lancarPago(atendente, 1, 1);
        long recusado = lancar(atendente, 1, 1);
        resultado(recusado, "RECUSADO");
        org.mockito.Mockito.clearInvocations(eventos); // ignora os envios feitos ao lançar os pedidos

        POST(admin(), "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.reenviados", greaterThanOrEqualTo(2))).andExpect(jsonPath("$.minutos").value(0));
        var reenviados = idsReenviados();
        org.junit.jupiter.api.Assertions.assertTrue(reenviados.contains(parado1) && reenviados.contains(parado2), "reenviados: " + reenviados);
        org.junit.jupiter.api.Assertions.assertFalse(reenviados.contains(pago), "pedido já pago não volta para o Pagamentos");
        org.junit.jupiter.api.Assertions.assertFalse(reenviados.contains(recusado), "pedido já recusado não volta para o Pagamentos");
    }

    @Test
    void reenfileirarRespeitaOTempoMinimoDeEspera() throws Exception {
        String atendente = token(Papel.ATENDENTE, novaLoja());
        long recem = lancar(atendente, 1, 1); // acabou de nascer: ainda não está "parado"
        org.mockito.Mockito.clearInvocations(eventos);
        POST(admin(), "/pedidos/reenfileirar", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.minutos").value(10)); // padrão: 10 min
        org.junit.jupiter.api.Assertions.assertFalse(idsReenviados().contains(recem));
    }

    @Test
    void reenfileirarSoOAdminFazEValidaOLimite() throws Exception {
        long loja = novaLoja();
        for (String t : new String[]{token(Papel.GERENTE, loja), token(Papel.ATENDENTE, loja), token(Papel.COZINHA, loja)}) {
            POST(t, "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isForbidden());
        }
        POST(null, "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isUnauthorized());
        POST(admin(), "/pedidos/reenfileirar?minutos=-1", "{}").andExpect(status().isBadRequest());
        POST(admin(), "/pedidos/reenfileirar?minutos=10081", "{}").andExpect(status().isBadRequest());
        POST(admin(), "/pedidos/reenfileirar?minutos=abc", "{}").andExpect(status().isBadRequest());
        POST(admin(), "/pedidos/reenfileirar?minutos=10080", "{}").andExpect(status().isOk());
    }

    @Test
    void reenfileirarVaiParaAAuditoria() throws Exception {
        POST(admin(), "/pedidos/reenfileirar?minutos=0", "{}").andExpect(status().isOk());
        GET(admin(), "/auditoria?limite=50").andExpect(jsonPath("$[?(@.acao=='PEDIDOS_REENFILEIRADOS')].detalhe",
                hasItem(containsString("aguardando há mais de 0 min"))));
    }
}
