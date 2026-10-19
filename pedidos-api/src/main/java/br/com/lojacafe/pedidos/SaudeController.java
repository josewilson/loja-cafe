package br.com.lojacafe.pedidos;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Público, para o monitoramento e para o Docker. Só diz ok/falha de cada parte, sem detalhes internos. */
@RestController
public class SaudeController {

    private final Saude saude;

    public SaudeController(Saude saude) {
        this.saude = saude;
    }

    @GetMapping("/saude")
    public ResponseEntity<Map<String, String>> saude() {
        boolean banco = saude.bancoVivo();
        boolean kafka = saude.kafkaVivo();
        Map<String, String> corpo = new LinkedHashMap<>();
        corpo.put("status", banco && kafka ? "ok" : "falha");
        corpo.put("banco", banco ? "ok" : "falha");
        corpo.put("kafka", kafka ? "ok" : "falha");
        return ResponseEntity.status(banco && kafka ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(corpo);
    }
}
