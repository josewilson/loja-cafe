package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoRepository repository;
    private final ProdutoRepository produtos;
    private final EstoqueLojaRepository estoque;
    private final PedidoEventos eventos;
    private final Auditor auditor;
    private final ConsumoInsumos consumo;

    public PedidoController(PedidoRepository repository, ProdutoRepository produtos, EstoqueLojaRepository estoque,
                            PedidoEventos eventos, Auditor auditor, ConsumoInsumos consumo) {
        this.consumo = consumo;
        this.repository = repository;
        this.produtos = produtos;
        this.estoque = estoque;
        this.eventos = eventos;
        this.auditor = auditor;
    }

    // Tudo numa transação: se faltar estoque ou o Kafka falhar, o estoque baixado e o pedido gravado voltam atrás.
    @PostMapping
    @Papeis({Papel.ATENDENTE, Papel.GERENTE})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(rollbackFor = Exception.class)
    public Pedido criar(@RequestAttribute(Autenticacao.USUARIO) Usuario usuario,
                        @Valid @RequestBody NovoPedido novo) throws Exception {
        FormaPagamento forma = novo.formaPagamento();
        if (novo.parcelas() != null && novo.parcelas() > forma.getParcelasMax()) {
            throw erro(forma.getNome() + " aceita no máximo " + forma.getParcelasMax() + " parcela(s)");
        }

        List<ItemPedido> itens = new ArrayList<>();
        for (NovoPedido.Item i : novo.itens()) {
            // O preço vem do cardápio da rede; produto desconhecido é erro do cliente (400).
            Produto produto = produtos.findById(i.produtoId()).orElseThrow(
                    () -> erro("produtoId " + i.produtoId() + " não existe"));
            if (!produto.isAtivo()) {
                throw erro("Produto " + produto.getNome() + " não está mais no cardápio");
            }
            // O estoque que baixa é o da loja de quem está lançando.
            if (estoque.baixar(usuario.getLojaId(), produto.getId(), i.quantidade()) == 0) {
                throw erro("Estoque insuficiente de " + produto.getNome());
            }
            itens.add(new ItemPedido(produto, i.quantidade(), i.tamanho(), i.extras()));
        }
        Pedido pedido = repository.save(new Pedido(usuario, novo.cliente(), forma, novo.parcelas(), novo.observacao(), itens));
        consumo.registrarEBaixar(pedido); // insumos da ficha técnica; nunca bloqueia a venda
        auditor.registrar(usuario, pedido.getLojaId(), "PEDIDO_CRIADO", "Pedido", pedido.getId(), "valor " + pedido.getValor());

        // Ato 3: anota o fato no Kafka e responde na hora; o Pagamentos lê o tópico no seu ritmo.
        eventos.publicarCriado(pedido);
        return pedido;
    }

    /** Da loja do usuário, mais novos primeiro. `limite` (padrão 100, de 1 a 200). O ADMIN vê todas (ou filtra por lojaId). */
    @GetMapping
    public List<Pedido> listar(@RequestAttribute(Autenticacao.USUARIO) Usuario usuario,
                               @RequestParam(defaultValue = "100") int limite,
                               @RequestParam(required = false) Long lojaId) {
        var pagina = PageRequest.of(0, Math.max(1, Math.min(limite, 200)));
        Long loja = Autenticacao.escopo(usuario, lojaId);
        return loja == null ? repository.findAllByOrderByIdDesc(pagina) : repository.findByLojaIdOrderByIdDesc(loja, pagina);
    }

    private static ResponseStatusException erro(String mensagem) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
