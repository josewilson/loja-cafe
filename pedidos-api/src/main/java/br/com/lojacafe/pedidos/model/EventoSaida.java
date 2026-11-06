package br.com.lojacafe.pedidos.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Um evento que a API ainda vai entregar (ou já entregou) ao Kafka. Ver service/Outbox. */
@Entity
@Table(name = "evento_saida")
public class EventoSaida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String topico;
    private String chave;
    @Column(length = 4000)
    private String payload;
    private Instant criadoEm;
    private Instant enviadoEm;

    protected EventoSaida() {
        // exigido pelo JPA
    }

    public EventoSaida(String topico, String chave, String payload) {
        this.topico = topico;
        this.chave = chave;
        this.payload = payload;
        this.criadoEm = Instant.now();
    }

    public void marcarEnviado() { this.enviadoEm = Instant.now(); }

    public Long getId() { return id; }
    public String getTopico() { return topico; }
    public String getChave() { return chave; }
    public String getPayload() { return payload; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getEnviadoEm() { return enviadoEm; }
}
