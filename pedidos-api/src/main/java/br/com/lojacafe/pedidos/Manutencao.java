package br.com.lojacafe.pedidos;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Faxina de hora em hora: sessões vencidas saem do banco e o contador de tentativas esquece quem já acalmou. */
@Component
public class Manutencao {

    private static final Logger log = LoggerFactory.getLogger(Manutencao.class);

    private final SessaoRepository sessoes;
    private final LimiteTentativas limite;

    public Manutencao(SessaoRepository sessoes, LimiteTentativas limite) {
        this.sessoes = sessoes;
        this.limite = limite;
    }

    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void limpar() {
        int apagadas = sessoes.apagarVencidas(Instant.now());
        limite.limpar(Duration.ofHours(1));
        if (apagadas > 0) {
            log.info("{} sessão(ões) vencida(s) removida(s)", apagadas);
        }
    }
}
