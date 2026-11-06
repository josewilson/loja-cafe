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

class AcessoTest extends ApiTestBase {

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

}
