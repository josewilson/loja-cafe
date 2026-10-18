package br.com.lojacafe.pedidos;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Ato 2: liga para o Pagamentos e ESPERA a resposta (specs/pagamentos.md). */
@Component
public class PagamentosClient {

    public record Pedir(Long pedidoId, BigDecimal valor) {}
    public record Resposta(Long pedidoId, String status, String motivo, String instancia) {}

    private final RestClient http = RestClient.create("http://localhost:5001");

    public Resposta pagar(Pedido pedido) {
        return http.post()
                .uri("/pagamentos")
                .body(new Pedir(pedido.getId(), pedido.getValor()))
                .retrieve()
                .body(Resposta.class);
    }
}
