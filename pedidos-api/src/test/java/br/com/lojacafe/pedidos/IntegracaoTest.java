package br.com.lojacafe.pedidos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Ponta a ponta com Postgres e Kafka de verdade (Testcontainers): migrações do Flyway, Outbox, Kafka e consumidor.
 * Sem Docker no computador, o teste é pulado. O Pagamentos (Python) é feito aqui pelo próprio teste, que publica o resultado.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "lojacafe.admin.email=admin@integracao.com", "lojacafe.admin.senha=admin12345", "lojacafe.bcrypt-custo=4",
        "KAFKA_USER=teste", "KAFKA_PASSWORD=teste"})
@AutoConfigureMockMvc
class IntegracaoTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void ligar(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT"); // o broker de teste não tem login
        r.add("spring.kafka.properties.sasl.mechanism", () -> "GSSAPI");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void pedidoViraEventoEOResultadoDeixaOPedidoPago() throws Exception {
        String admin = login("admin@integracao.com", "admin12345");
        long loja = id(enviar(post("/lojas"), admin, "{\"nome\":\"Loja integração\",\"estoqueInicial\":50}"));
        String email = UUID.randomUUID() + "@exemplo.com";
        enviar(post("/usuarios"), admin, "{\"nome\":\"Ana\",\"email\":\"" + email + "\",\"senha\":\"segredo123\",\"papel\":\"ATENDENTE\",\"lojaId\":" + loja + "}");
        String atendente = login(email, "segredo123");

        long pedido = id(enviar(post("/pedidos"), atendente, "{\"formaPagamento\":\"PIX\",\"itens\":[{\"produtoId\":1,\"quantidade\":2}]}"));

        // 1) a Outbox entrega o evento ao Kafka
        var criado = esperarMensagem("pedidos-criados", String.valueOf(pedido));
        assertEquals(pedido, ((Number) JsonPath.read(criado.value(), "$.pedidoId")).longValue());

        // 2) o Pagamentos responde; o consumidor marca o pedido como PAGO
        publicar("pagamentos-resultados", String.valueOf(pedido),
                "{\"versao\":1,\"pedidoId\":" + pedido + ",\"status\":\"APROVADO\",\"motivo\":null,\"instancia\":\"teste\"}");
        esperar(() -> "PAGO".equals(statusDo(atendente, pedido)), "pedido " + pedido + " ficar PAGO");
    }

    @Test
    void mensagemRuimVaiParaADeadLetterNaMesmaParticao() throws Exception {
        String chave = UUID.randomUUID().toString();
        publicar("pagamentos-resultados", chave, "{isto não é json");
        var morta = esperarMensagem("pagamentos-resultados.DLT", chave);
        assertEquals("{isto não é json", morta.value());
    }

    // ---------- ajudantes ----------

    private String login(String email, String senha) throws Exception {
        var corpo = mvc.perform(post("/auth/entrar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.token");
    }

    private String enviar(MockHttpServletRequestBuilder req, String token, String json) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static long id(String corpo) {
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    private String statusDo(String token, long pedido) {
        try {
            var corpo = mvc.perform(get("/pedidos").header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString();
            List<String> s = JsonPath.read(corpo, "$[?(@.id==" + pedido + ")].status");
            return s.isEmpty() ? null : s.get(0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void publicar(String topico, String chave, String valor) throws Exception {
        try (var p = new KafkaProducer<String, String>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            p.send(new ProducerRecord<>(topico, chave, valor)).get();
        }
    }

    /** Lê o tópico desde o início até achar uma mensagem com essa chave (ou desiste em 30 s). */
    private ConsumerRecord<String, String> esperarMensagem(String topico, String chave) {
        try (var c = new KafkaConsumer<String, String>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of(topico));
            long fim = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < fim) {
                for (var r : c.poll(Duration.ofMillis(500))) {
                    if (chave.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        return fail("nenhuma mensagem com chave " + chave + " em " + topico);
    }

    private static void esperar(BooleanSupplier condicao, String oQue) throws InterruptedException {
        long fim = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < fim) {
            if (condicao.getAsBoolean()) {
                return;
            }
            Thread.sleep(300);
        }
        fail("esperei 30 s por: " + oQue);
    }
}
