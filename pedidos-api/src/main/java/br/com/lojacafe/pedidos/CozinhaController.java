package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Fila de preparo, retirada e cancelamento (specs/api.md). O andamento (preparo) é separado do pagamento (status). */
@RestController
public class CozinhaController {

    public record NovoPreparo(@NotNull Preparo preparo) {}
    public record Cancelamento(@Size(max = 140) String motivo) {}

    private final PedidoRepository repository;
    private final EstoqueLojaRepository estoque;
    private final AuditoriaRepository auditor;
    private final FichaController consumo;
    private final PagamentosKafka pagamentos;

    public CozinhaController(PedidoRepository repository, EstoqueLojaRepository estoque, AuditoriaRepository auditor, FichaController consumo,
                              PagamentosKafka pagamentos) {
        this.pagamentos = pagamentos;
        this.consumo = consumo;
        this.repository = repository;
        this.estoque = estoque;
        this.auditor = auditor;
    }

    /** A fila da loja: só pedidos PAGO ainda não retirados nem cancelados, do mais antigo ao mais novo. */
    @GetMapping("/cozinha")
    public List<Pedido> fila(@RequestAttribute(AuthController.USUARIO) Usuario usuario,
                             @RequestParam(defaultValue = "100") int limite,
                             @RequestParam(required = false) Long lojaId) {
        Long loja = AuthController.escopo(usuario, lojaId);
        if (loja == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe lojaId");
        }
        return repository.findByLojaIdAndStatusAndPreparoInOrderByIdAsc(loja, StatusPedido.PAGO,
                Set.of(Preparo.NA_FILA, Preparo.PREPARANDO, Preparo.PRONTO), PageRequest.of(0, Math.max(1, Math.min(limite, 200))));
    }

    @PutMapping("/pedidos/{id}/preparo")
    @Papeis({Papel.COZINHA, Papel.GERENTE, Papel.ATENDENTE})
    @Transactional
    public Pedido mudarPreparo(@RequestAttribute(AuthController.USUARIO) Usuario usuario,
                               @PathVariable Long id, @Valid @RequestBody NovoPreparo corpo) {
        Preparo novo = corpo.preparo();
        // Quem pode dar cada passo: a cozinha prepara; o balcão entrega; o gerente faz os dois.
        Set<Papel> permitidos = switch (novo) {
            case PREPARANDO, PRONTO -> Set.of(Papel.COZINHA, Papel.GERENTE);
            case RETIRADO -> Set.of(Papel.ATENDENTE, Papel.GERENTE);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use PREPARANDO, PRONTO ou RETIRADO (cancelar é em /cancelar)");
        };
        if (!permitidos.contains(usuario.getPapel())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seu papel não dá este passo");
        }
        Pedido pedido = carregar(usuario, id);
        if (pedido.getStatus() != StatusPedido.PAGO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O pedido ainda não foi pago");
        }
        Preparo antes = pedido.getPreparo();
        if (!pedido.avancarPara(novo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O pedido está " + antes + "; só avança de " + novo.anterior() + " para " + novo);
        }
        auditor.registrar(usuario, pedido.getLojaId(), "PREPARO_ALTERADO", "Pedido", id, antes + " -> " + novo);
        return repository.save(pedido);
    }

    public record Pagamento(boolean aprovado, @Size(max = 30) String nsu) {}

    /**
     * Cartão passado na maquininha: o atendente diz se foi aprovado (com o NSU do comprovante, se quiser) ou recusado.
     * Só vale em pedido CREDITO ou DEBITO que ainda aguarda o pagamento. Recusado devolve estoque e insumos, como no Pagamentos.
     */
    @PutMapping("/pedidos/{id}/pagamento")
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    @Transactional
    public Pedido confirmarPagamento(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @PathVariable Long id,
                                     @Valid @RequestBody Pagamento corpo) {
        Pedido pedido = carregar(usuario, id);
        if (pedido.getFormaPagamento() != FormaPagamento.CREDITO && pedido.getFormaPagamento() != FormaPagamento.DEBITO) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só pagamento em cartão (crédito ou débito) é confirmado à mão");
        }
        String motivo = corpo.aprovado() ? null : "recusado na maquininha";
        if (!pagamentos.concluir(pedido, corpo.aprovado(), motivo, "maquininha")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "O pagamento deste pedido já foi decidido");
        }
        pedido.registrarNsu(corpo.aprovado() ? corpo.nsu() : null);
        auditor.registrar(usuario, pedido.getLojaId(), corpo.aprovado() ? "PAGAMENTO_CONFIRMADO" : "PAGAMENTO_RECUSADO", "Pedido", id,
                pedido.getNsu() == null ? null : "nsu " + pedido.getNsu());
        return repository.save(pedido);
    }

    @PutMapping("/pedidos/{id}/cancelar")
    @Papeis(Papel.GERENTE)
    @Transactional
    public Pedido cancelar(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @PathVariable Long id,
                           @Valid @RequestBody(required = false) Cancelamento corpo) {
        Pedido pedido = carregar(usuario, id);
        Preparo antes = pedido.getPreparo();
        if (!pedido.cancelar()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este pedido não pode mais ser cancelado");
        }
        // A trava da linha garante que só um cancelamento chega aqui: o estoque volta uma única vez.
        pedido.getItens().forEach(i -> estoque.repor(pedido.getLojaId(), i.getProdutoId(), i.getQuantidade()));
        // Insumos: só voltam se ainda estava na fila. Se a cozinha já começou, foram usados de verdade (perda).
        if (antes == Preparo.NA_FILA) {
            consumo.devolver(pedido);
        }
        String motivo = corpo == null || corpo.motivo() == null ? "" : corpo.motivo().trim();
        auditor.registrar(usuario, pedido.getLojaId(), "PEDIDO_CANCELADO", "Pedido", id, motivo.isEmpty() ? null : motivo);
        return repository.save(pedido);
    }

    /** Registra que o dinheiro do pedido pago e cancelado foi devolvido (na maquininha ou no app do Pix). O sistema não move dinheiro. */
    @PutMapping("/pedidos/{id}/estorno")
    @Papeis(Papel.GERENTE)
    @Transactional
    public Pedido estornar(@RequestAttribute(AuthController.USUARIO) Usuario usuario, @PathVariable Long id,
                           @Valid @RequestBody(required = false) Cancelamento corpo) {
        Pedido pedido = carregar(usuario, id);
        if (!pedido.estornar()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Só se estorna pedido pago e cancelado, uma vez");
        }
        String motivo = corpo == null || corpo.motivo() == null ? "" : corpo.motivo().trim();
        auditor.registrar(usuario, pedido.getLojaId(), "PAGAMENTO_ESTORNADO", "Pedido", id, motivo.isEmpty() ? null : motivo);
        return repository.save(pedido);
    }

    /** Lê o pedido com trava, e só o da loja do usuário (outra loja é 403). */
    private Pedido carregar(Usuario usuario, Long id) {
        Pedido pedido = repository.findWithLockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido não existe"));
        AuthController.escopo(usuario, pedido.getLojaId());
        return pedido;
    }
}
