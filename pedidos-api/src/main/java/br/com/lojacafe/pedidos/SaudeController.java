package br.com.lojacafe.pedidos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Público, para o monitoramento e para o Docker (GET /saude). Só diz ok/falha do banco e do Kafka, sem detalhes internos. */
@RestController
public class SaudeController {

    private static final long VALIDADE_KAFKA_MS = 10_000;

    private final JdbcTemplate jdbc;
    private final KafkaAdmin kafkaAdmin;
    // Abrir uma conexão com o Kafka a cada chamada seria caro (o monitoramento chama a toda hora): o resultado vale 10 s.
    private volatile long kafkaVerificadoEm;
    private volatile boolean kafkaOk;

    public SaudeController(JdbcTemplate jdbc, KafkaAdmin kafkaAdmin) {
        this.jdbc = jdbc;
        this.kafkaAdmin = kafkaAdmin;
    }

    @GetMapping("/saude")
    public ResponseEntity<Map<String, String>> saude() {
        boolean banco = bancoVivo();
        boolean kafka = kafkaVivo();
        Map<String, String> corpo = new LinkedHashMap<>();
        corpo.put("status", banco && kafka ? "ok" : "falha");
        corpo.put("banco", banco ? "ok" : "falha");
        corpo.put("kafka", kafka ? "ok" : "falha");
        return ResponseEntity.status(banco && kafka ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(corpo);
    }

    public boolean bancoVivo() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean kafkaVivo() {
        long agora = System.currentTimeMillis();
        if (agora - kafkaVerificadoEm < VALIDADE_KAFKA_MS) {
            return kafkaOk;
        }
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put("request.timeout.ms", 2000);
        config.put("default.api.timeout.ms", 3000);
        boolean vivo;
        try (AdminClient cliente = AdminClient.create(config)) {
            vivo = !cliente.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty();
        } catch (Exception e) {
            vivo = false;
        }
        kafkaOk = vivo;
        kafkaVerificadoEm = agora;
        return vivo;
    }
}
