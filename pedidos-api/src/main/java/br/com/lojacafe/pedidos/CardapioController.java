package br.com.lojacafe.pedidos;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cardápio e opções que o front desenha na tela (specs/api.md). */
@RestController
public class CardapioController {

    public record OpcaoTamanho(Tamanho codigo, String nome, BigDecimal ajuste) {}
    public record OpcaoExtra(Extra codigo, String nome, BigDecimal preco) {}
    public record OpcaoPagamento(FormaPagamento codigo, String nome, int parcelasMax) {}
    public record Opcoes(List<OpcaoTamanho> tamanhos, List<OpcaoExtra> extras, List<OpcaoPagamento> formasPagamento) {}

    private final ProdutoRepository produtos;

    public CardapioController(ProdutoRepository produtos) {
        this.produtos = produtos;
    }

    @GetMapping("/produtos")
    public List<Produto> produtos() {
        return produtos.findAllByOrderByIdAsc();
    }

    @GetMapping("/opcoes")
    public Opcoes opcoes() {
        return new Opcoes(
                Arrays.stream(Tamanho.values()).map(t -> new OpcaoTamanho(t, t.getNome(), t.getAjuste())).toList(),
                Arrays.stream(Extra.values()).map(e -> new OpcaoExtra(e, e.getNome(), e.getPreco())).toList(),
                Arrays.stream(FormaPagamento.values()).map(f -> new OpcaoPagamento(f, f.getNome(), f.getParcelasMax())).toList());
    }
}
