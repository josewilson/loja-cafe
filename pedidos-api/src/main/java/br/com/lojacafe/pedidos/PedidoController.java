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
    private final PedidoEventos eventos;

    public PedidoController(PedidoRepository repository, ProdutoRepository produtos, PedidoEventos eventos) {
        this.repository = repository;
        this.produtos = produtos;
        this.eventos = eventos;
    }

    // Tudo numa transação: se faltar estoque ou o Kafka falhar, o estoque baixado e o pedido gravado voltam atrás.
    @PostMapping
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
            // O preço vem do cardápio da API; produto desconhecido é erro do cliente (400).
            Produto produto = produtos.findById(i.produtoId()).orElseThrow(
                    () -> erro("produtoId " + i.produtoId() + " não existe"));
            if (produtos.baixar(produto.getId(), i.quantidade()) == 0) {
                throw erro("Estoque insuficiente de " + produto.getNome());
            }
            itens.add(new ItemPedido(produto, i.quantidade(), i.tamanho(), i.extras()));
        }
        Pedido pedido = repository.save(new Pedido(usuario, forma, novo.parcelas(), novo.observacao(), itens));

        // Ato 3: anota o fato no Kafka e responde na hora; o Pagamentos lê o tópico no seu ritmo.
        eventos.publicarCriado(pedido);
        return pedido;
    }

    /** Só os pedidos de quem está logado, mais novos primeiro. `limite` (padrão 100, de 1 a 200). */
    @GetMapping
    public List<Pedido> listar(@RequestAttribute(Autenticacao.USUARIO) Usuario usuario,
                               @RequestParam(defaultValue = "100") int limite) {
        return repository.findByUsuarioOrderByIdDesc(usuario, PageRequest.of(0, Math.max(1, Math.min(limite, 200))));
    }

    private static ResponseStatusException erro(String mensagem) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
