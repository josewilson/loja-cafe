package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.service.PedidoService;
import br.com.lojacafe.pedidos.service.PedidoService.NovoPedido;
import br.com.lojacafe.pedidos.service.PedidoService.Recibo;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Pedidos do balcão (specs/api.md). As regras estão em {@link PedidoService}. */
@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoService pedidos;

    public PedidoController(PedidoService pedidos) {
        this.pedidos = pedidos;
    }

    @PostMapping
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    @ResponseStatus(HttpStatus.CREATED)
    public Pedido criar(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @Valid @RequestBody NovoPedido novo) {
        return pedidos.criar(usuario, novo);
    }

    /** `limite` (padrão 100, de 1 a 200). */
    @GetMapping
    public List<Pedido> listar(@RequestAttribute(Porteiro.USUARIO) Usuario usuario,
                               @RequestParam(defaultValue = "100") int limite,
                               @RequestParam(required = false) Long lojaId) {
        return pedidos.listar(usuario, limite, lojaId);
    }

    @GetMapping("/{id}/recibo")
    public Recibo recibo(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @PathVariable Long id) {
        return pedidos.recibo(usuario, id);
    }

    @PostMapping("/{id}/nfce")
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    public Pedido emitirNfce(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @PathVariable Long id) {
        return pedidos.emitirNfce(usuario, id);
    }
}
