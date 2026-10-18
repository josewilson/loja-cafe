package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoRepository repository;
    private final PedidoEventos eventos;

    public PedidoController(PedidoRepository repository, PedidoEventos eventos) {
        this.repository = repository;
        this.eventos = eventos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Pedido criar(@Valid @RequestBody NovoPedido novo) throws Exception {
        Pedido pedido = new Pedido(novo.cliente(), novo.item(), novo.quantidade(), novo.precoUnitario());
        pedido = repository.save(pedido);

        // Ato 3: em vez de ligar para o Pagamentos, anota o fato no Kafka e responde na hora.
        // Quem quiser saber (Pagamentos, Analytics...) lê o tópico no seu ritmo.
        eventos.publicarCriado(pedido);
        return pedido;
    }

    @GetMapping
    public List<Pedido> listar() {
        return repository.findAllByOrderByIdDesc();
    }
}
