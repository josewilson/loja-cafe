package br.com.lojacafe.pedidos;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// Testa a API de verdade (HTTP + validação + banco), sem Kafka e com H2 em memória (não mexe no Postgres).
@SpringBootTest(properties = {
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:teste"
})
@AutoConfigureMockMvc
class PedidosApiApplicationTests {

    @Autowired
    MockMvc mvc;

    // O envio ao Kafka é só um "ok" aqui: não existe broker nos testes.
    @MockitoBean
    PedidoEventos eventos;

    private ResultActions postar(String json) throws Exception {
        return mvc.perform(post("/pedidos").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void contextLoads() {
    }

    @Test
    void cardapioVemDaApi() throws Exception {
        mvc.perform(get("/produtos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].nome").value("Café coado"))
                .andExpect(jsonPath("$[0].preco").value(8.5));
        mvc.perform(get("/opcoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanhos", hasSize(3)))
                .andExpect(jsonPath("$.extras", hasSize(4)));
    }

    @Test
    void postValidoCalculaOValorNoServidor() throws Exception {
        // Cappuccino 12,00 + Grande 3,00 + Chantilly 2,00 + Canela 0,50 = 17,50 × 2 = 35,00
        postar("""
                {"cliente":"Ana","produtoId":2,"quantidade":2,"tamanho":"GRANDE",
                 "extras":["CHANTILLY","CANELA"],"observacao":"sem açúcar"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.item").value("Cappuccino"))
                .andExpect(jsonPath("$.valor").value(35.0))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"))
                .andExpect(jsonPath("$.extras", contains("CHANTILLY", "CANELA")))
                .andExpect(jsonPath("$.observacao").value("sem açúcar"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void precoEnviadoPeloClienteEIgnorado() throws Exception {
        postar("{\"cliente\":\"Ana\",\"produtoId\":1,\"quantidade\":1,\"precoUnitario\":0.01}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valor").value(8.5));
    }

    @Test
    void entradasInvalidasDao400() throws Exception {
        postar("{\"cliente\":\"Ana\",\"produtoId\":99,\"quantidade\":1}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"\",\"produtoId\":1,\"quantidade\":1}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"" + "A".repeat(61) + "\",\"produtoId\":1,\"quantidade\":1}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"Ana\",\"produtoId\":1,\"quantidade\":0}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"Ana\",\"produtoId\":1,\"quantidade\":51}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"Ana\",\"produtoId\":1,\"quantidade\":1,\"tamanho\":\"XG\"}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"Ana\",\"produtoId\":1,\"quantidade\":1,\"extras\":[\"GELO\"]}").andExpect(status().isBadRequest());
        postar("{\"cliente\":\"Ana\",\"produtoId\":1,\"quantidade\":1,\"observacao\":\"" + "x".repeat(141) + "\"}")
                .andExpect(status().isBadRequest());
        postar("{\"cliente\":").andExpect(status().isBadRequest());
    }

    @Test
    void erroDeValidacaoDizQualCampoFalhou() throws Exception {
        // O corpo JSON do erro vem do /error, que o MockMvc não percorre: aqui olhamos a exceção.
        // O corpo real (com "errors") foi conferido com a API no ar.
        postar("{\"cliente\":\"\",\"produtoId\":1,\"quantidade\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    var erro = (org.springframework.web.bind.MethodArgumentNotValidException) result.getResolvedException();
                    var campos = erro.getBindingResult().getFieldErrors().stream()
                            .map(e -> e.getField()).toList();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            campos.containsAll(java.util.List.of("cliente", "quantidade")), "campos: " + campos);
                });
    }

    @Test
    void listaRespeitaOLimiteEMostraOMaisNovoPrimeiro() throws Exception {
        postar("{\"cliente\":\"Primeiro\",\"produtoId\":1,\"quantidade\":1}").andExpect(status().isCreated());
        postar("{\"cliente\":\"Segundo\",\"produtoId\":1,\"quantidade\":1}").andExpect(status().isCreated());
        mvc.perform(get("/pedidos").param("limite", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cliente").value("Segundo"));
    }
}
