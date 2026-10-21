package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.AuthController.Papeis;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.StatusPedido;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.AuditoriaRepository;
import br.com.lojacafe.pedidos.repository.PedidoRepository;
import br.com.lojacafe.pedidos.service.PagamentosKafka;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reenvia ao Pagamentos os pedidos que ficaram aguardando (specs/api.md). Serve depois de restaurar um backup
 * (o Kafka não entra no backup) ou de uma falha longa do Pagamentos. Repetir é seguro: o primeiro resultado vale.
 */
@RestController
public class ReenfileirarController {

    public record Resultado(int reenviados, int minutos) {}

    private static final int MAX_POR_CHAMADA = 500;
    private static final int MAX_MINUTOS = 10_080; // 7 dias, o quanto o Kafka guarda

    private final PedidoRepository repository;
    private final PagamentosKafka eventos;
    private final AuditoriaRepository auditor;

    public ReenfileirarController(PedidoRepository repository, PagamentosKafka eventos, AuditoriaRepository auditor) {
        this.repository = repository;
        this.eventos = eventos;
        this.auditor = auditor;
    }

    @PostMapping("/pedidos/reenfileirar")
    @Papeis(Papel.ADMIN)
    @Transactional
    public Resultado reenfileirar(@RequestAttribute(AuthController.USUARIO) Usuario quem,
                                  @RequestParam(defaultValue = "10") int minutos) throws Exception {
        if (minutos < 0 || minutos > MAX_MINUTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutos deve ficar entre 0 e " + MAX_MINUTOS);
        }
        List<Pedido> parados = repository.findByStatusAndCriadoEmBeforeOrderByIdAsc(StatusPedido.AGUARDANDO_PAGAMENTO,
                Instant.now().minus(minutos, ChronoUnit.MINUTES), PageRequest.of(0, MAX_POR_CHAMADA));
        for (Pedido pedido : parados) {
            eventos.publicarCriado(pedido);
        }
        auditor.registrar(quem, null, "PEDIDOS_REENFILEIRADOS", "Pedido", null,
                parados.size() + " pedido(s) aguardando há mais de " + minutos + " min");
        return new Resultado(parados.size(), minutos);
    }
}
