package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.config.AuthInterceptor.Papeis;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Pedido;
import br.com.lojacafe.pedidos.model.Preparo;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.service.PedidoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Fila de preparo, retirada e cancelamento (specs/api.md). O andamento (preparo) é separado do pagamento (status). Regras em {@link PedidoService}. */
@RestController
public class CozinhaController {

    public record NovoPreparo(@NotNull Preparo preparo) {}
    public record Cancelamento(@Size(max = 140) String motivo) {}
    public record Pagamento(boolean aprovado, @Size(max = 30) String nsu) {}

    private final PedidoService pedidos;

    public CozinhaController(PedidoService pedidos) {
        this.pedidos = pedidos;
    }

    @GetMapping("/cozinha")
    public List<Pedido> fila(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario,
                             @RequestParam(defaultValue = "100") int limite,
                             @RequestParam(required = false) Long lojaId) {
        return pedidos.fila(usuario, limite, lojaId);
    }

    @PutMapping("/pedidos/{id}/preparo")
    @Papeis({Papel.COZINHA, Papel.GERENTE, Papel.ATENDENTE})
    public Pedido mudarPreparo(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario,
                               @PathVariable Long id, @Valid @RequestBody NovoPreparo corpo) {
        return pedidos.mudarPreparo(usuario, id, corpo.preparo());
    }

    @PutMapping("/pedidos/{id}/pagamento")
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    public Pedido confirmarPagamento(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario, @PathVariable Long id,
                                     @Valid @RequestBody Pagamento corpo) {
        return pedidos.confirmarPagamento(usuario, id, corpo.aprovado(), corpo.nsu());
    }

    @PutMapping("/pedidos/{id}/cancelar")
    @Papeis(Papel.GERENTE)
    public Pedido cancelar(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario, @PathVariable Long id,
                           @Valid @RequestBody(required = false) Cancelamento corpo) {
        return pedidos.cancelar(usuario, id, corpo == null ? null : corpo.motivo());
    }

    @PutMapping("/pedidos/{id}/estorno")
    @Papeis(Papel.GERENTE)
    public Pedido estornar(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario, @PathVariable Long id,
                           @Valid @RequestBody(required = false) Cancelamento corpo) {
        return pedidos.estornar(usuario, id, corpo == null ? null : corpo.motivo());
    }
}
