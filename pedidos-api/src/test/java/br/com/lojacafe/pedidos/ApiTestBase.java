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

// Testa a API de verdade (HTTP + login + papéis + validação + banco), sem Kafka e com H2 em memória (não mexe no Postgres).
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:teste",
        "DB_PASSWORD=teste", "KAFKA_USER=teste", "KAFKA_PASSWORD=teste", // sem Kafka nem Postgres de verdade aqui
        "lojacafe.admin.email=admin@teste.com", "lojacafe.admin.senha=admin12345",
        "lojacafe.bcrypt-custo=4", // BCrypt no custo mínimo: nos testes a velocidade importa mais que a força (em produção o padrão é 10)
        // Nos testes o Hibernate cria as tabelas no H2 e o cardápio vem de seed-teste.sql (as migrações são para o Postgres).
        "lojacafe.nfce.provedor=simulado", // NFC-e de mentira (Fase 6b)
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.defer-datasource-initialization=true",
        "spring.sql.init.mode=always",
        "spring.sql.init.data-locations=classpath:seed-teste.sql"
})
@AutoConfigureMockMvc
abstract class ApiTestBase {

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
        org.mockito.Mockito.doNothing().when(eventos).publicarEstorno(org.mockito.ArgumentMatchers.any());
    }

    // ---------- ajudantes ----------

    protected ResultActions GET(String token, String caminho) throws Exception {
        var req = get(caminho);
        return mvc.perform(token == null ? req : req.header("Authorization", "Bearer " + token));
    }

    protected ResultActions enviar(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                                 String token, String json) throws Exception {
        req = req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(token == null ? req : req.header("Authorization", "Bearer " + token));
    }

    protected ResultActions POST(String token, String caminho, String json) throws Exception {
        return enviar(post(caminho), token, json);
    }

    protected ResultActions PUT(String token, String caminho, String json) throws Exception {
        return enviar(put(caminho), token, json);
    }

    protected ResultActions entrar(String email, String senha) throws Exception {
        return POST(null, "/auth/entrar", "{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}");
    }

    protected static String texto(ResultActions r, String caminho) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), caminho).toString();
    }

    protected String tokenDe(String email, String senha) throws Exception {
        return texto(entrar(email, senha).andExpect(status().isOk()), "$.token");
    }

    // O login gasta BCrypt (devagar de propósito): o token do admin é pedido uma vez só.
    protected static String tokenAdmin;

    protected String admin() throws Exception {
        if (tokenAdmin == null) {
            tokenAdmin = tokenDe("admin@teste.com", "admin12345");
        }
        return tokenAdmin;
    }

    /** Cria uma loja nova (50 unidades de cada produto) e devolve o id. */
    protected long novaLoja() throws Exception {
        var r = POST(admin(), "/lojas", "{\"nome\":\"Loja " + UUID.randomUUID() + "\",\"estoqueInicial\":50}").andExpect(status().isCreated());
        return Long.parseLong(texto(r, "$.id"));
    }

    protected static final String SENHA = "segredo123";

    /** Cria um usuário com o papel na loja (e-mail único) e devolve o e-mail. */
    protected String novoUsuario(Papel papel, Long lojaId) throws Exception {
        String email = UUID.randomUUID() + "@exemplo.com";
        String loja = lojaId == null ? "" : ",\"lojaId\":" + lojaId;
        POST(admin(), "/usuarios", "{\"nome\":\"" + papel + "\",\"email\":\"" + email + "\",\"senha\":\"" + SENHA
                + "\",\"papel\":\"" + papel + "\"" + loja + "}").andExpect(status().isCreated());
        return email;
    }

    protected String token(Papel papel, long lojaId) throws Exception {
        return tokenDe(novoUsuario(papel, lojaId), SENHA);
    }

    protected static String pedido(String itens) {
        return "{\"formaPagamento\":\"PIX\",\"itens\":" + itens + "}";
    }

    protected static String umItem(int produtoId, int quantidade) {
        return pedido("[{\"produtoId\":" + produtoId + ",\"quantidade\":" + quantidade + "}]");
    }

    @SuppressWarnings("unchecked")
    protected int estoque(String token, int produtoId) throws Exception {
        String corpo = GET(token, "/produtos").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((List<Integer>) JsonPath.read(corpo, "$[?(@.id==" + produtoId + ")].estoque")).get(0);
    }


    /** Lança um pedido (produto, quantidade) como esse atendente e devolve o id. O pagamento fica aguardando. */
    protected long lancar(String token, int produtoId, int quantidade) throws Exception {
        String corpo = POST(token, "/pedidos", umItem(produtoId, quantidade)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    protected void resultado(long pedidoId, String status) {
        listener.receber("{\"versao\":1,\"pedidoId\":" + pedidoId + ",\"status\":\"" + status
                + "\",\"motivo\":null,\"instancia\":\"caixa-1\"}");
    }

    /** Lança e já deixa o pedido PAGO. */
    protected long lancarPago(String token, int produtoId, int quantidade) throws Exception {
        long id = lancar(token, produtoId, quantidade);
        resultado(id, "APROVADO");
        return id;
    }

    @MockitoSpyBean
    SaudeController saude;


    /** Cria um produto novo (nome único) e devolve o id. */
    protected long novoProduto(String preco) throws Exception {
        String corpo = "{\"nome\":\"Produto " + UUID.randomUUID() + "\",\"descricao\":\"teste\",\"emoji\":\"🥐\",\"preco\":" + preco + "}";
        return Long.parseLong(texto(POST(admin(), "/cardapio", corpo).andExpect(status().isCreated()), "$.id"));
    }

    protected ResultActions preparo(String token, long id, String novo) throws Exception {
        return PUT(token, "/pedidos/" + id + "/preparo", "{\"preparo\":\"" + novo + "\"}");
    }

    /** Lança um pedido com essa forma de pagamento e já o deixa PAGO. Preços: produto 1 = 8,50, 2 = 12,00, 3 = 6,00, 4 = 9,50. */
    protected long pagoComo(String token, String forma, int produtoId, int quantidade) throws Exception {
        String corpo = POST(token, "/pedidos", "{\"formaPagamento\":\"" + forma + "\",\"itens\":[{\"produtoId\":" + produtoId
                + ",\"quantidade\":" + quantidade + "}]}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        resultado(id, "APROVADO");
        return id;
    }
}
