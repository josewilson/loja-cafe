package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoRepository repository;
    private final PagamentosClient pagamentos;

    public PedidoController(PedidoRepository repository, PagamentosClient pagamentos) {
        this.repository = repository;
        this.pagamentos = pagamentos;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Pedido criar(@Valid @RequestBody NovoPedido novo) {
        Pedido pedido = new Pedido(novo.cliente(), novo.item(), novo.quantidade(), novo.precoUnitario());
        pedido = repository.save(pedido);

        // Ato 2: liga para o Pagamentos e ESPERA (~3 s) antes de responder ao front.
        // Sem try/catch de propósito: se o Pagamentos cair, queremos ver o que acontece.
        var resposta = pagamentos.pagar(pedido);
        pedido.concluir("APROVADO".equals(resposta.status()), resposta.motivo(), resposta.instancia());
        return repository.save(pedido);
    }

    @GetMapping
    public List<Pedido> listar() {
        return repository.findAllByOrderByIdDesc();
    }
}
