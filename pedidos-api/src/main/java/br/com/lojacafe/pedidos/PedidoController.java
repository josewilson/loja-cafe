package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoRepository repository;
    private final ProdutoRepository produtos;
    private final PedidoEventos eventos;

    public PedidoController(PedidoRepository repository, ProdutoRepository produtos, PedidoEventos eventos) {
        this.repository = repository;
        this.produtos = produtos;
        this.eventos = eventos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Pedido criar(@Valid @RequestBody NovoPedido novo) throws Exception {
        // O preço vem do cardápio da API; produto desconhecido é erro do cliente (400).
        Produto produto = produtos.findById(novo.produtoId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "produtoId " + novo.produtoId() + " não existe"));
        Pedido pedido = repository.save(new Pedido(novo.cliente(), produto, novo.quantidade(),
                novo.tamanho(), novo.extras(), novo.observacao()));

        // Ato 3: anota o fato no Kafka e responde na hora; o Pagamentos lê o tópico no seu ritmo.
        eventos.publicarCriado(pedido);
        return pedido;
    }

    /** Mais novos primeiro. `limite` (padrão 100, de 1 a 200) evita devolver o histórico inteiro. */
    @GetMapping
    public List<Pedido> listar(@RequestParam(defaultValue = "100") int limite) {
        return repository.findAllByOrderByIdDesc(PageRequest.of(0, Math.max(1, Math.min(limite, 200))));
    }
}
