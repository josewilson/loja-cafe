package br.com.lojacafe.pedidos.controller;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

/** Público, para o monitoramento e para o Docker (GET /saude). Só diz ok/falha do banco, do Kafka e da Outbox, sem detalhes internos. */
@RestController
public class SaudeController {

    private static final long VALIDADE_KAFKA_MS = 10_000;
    // ponytail: 60 s fixos; evento pendente há mais que isso = o relé não está conseguindo entregar ao Kafka. Virar configuração se precisar.
    private static final Duration OUTBOX_ATRASO = Duration.ofSeconds(60);

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
        boolean outbox = outboxEmDia();
        boolean tudo = banco && kafka && outbox;
        Map<String, String> corpo = new LinkedHashMap<>();
        corpo.put("status", tudo ? "ok" : "falha");
        corpo.put("banco", banco ? "ok" : "falha");
        corpo.put("kafka", kafka ? "ok" : "falha");
        corpo.put("outbox", outbox ? "ok" : "atrasada");
        return ResponseEntity.status(tudo ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(corpo);
    }

    public boolean bancoVivo() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** A fila da Outbox anda? Falso se algum evento espera há mais de 60 s para chegar ao Kafka. */
    public boolean outboxEmDia() {
        try {
            Long atrasados = jdbc.queryForObject("select count(*) from evento_saida where enviado_em is null and criado_em < ?",
                    Long.class, Timestamp.from(Instant.now().minus(OUTBOX_ATRASO)));
            return atrasados == null || atrasados == 0;
        } catch (Exception e) {
            return true; // sem banco o "banco: falha" já derruba o status; aqui não duplica o alarme
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
