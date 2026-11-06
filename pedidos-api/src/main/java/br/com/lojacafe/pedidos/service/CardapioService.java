package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.Extra;
import br.com.lojacafe.pedidos.model.FormaPagamento;
import br.com.lojacafe.pedidos.model.ProdutoComEstoque;
import br.com.lojacafe.pedidos.model.Tamanho;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.ProdutoRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/** Cardápio e opções que o front desenha na tela (specs/api.md). */
@Service
public class CardapioService {

    public record OpcaoTamanho(Tamanho codigo, String nome, BigDecimal ajuste, BigDecimal fatorInsumo) {}
    public record OpcaoExtra(Extra codigo, String nome, BigDecimal preco) {}
    public record OpcaoPagamento(FormaPagamento codigo, String nome, int parcelasMax) {}
    public record Opcoes(List<OpcaoTamanho> tamanhos, List<OpcaoExtra> extras, List<OpcaoPagamento> formasPagamento) {}

    private final ProdutoRepository produtos;

    public CardapioService(ProdutoRepository produtos) {
        this.produtos = produtos;
    }

    /** Cardápio da rede com o estoque da loja do usuário. O ADMIN (sem loja) escolhe com ?lojaId=. */
    public List<ProdutoComEstoque> produtos(Usuario usuario,
                                            Long lojaId) {
        Long loja = Acesso.escopo(usuario, lojaId);
        if (loja == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe lojaId");
        }
        return produtos.cardapio(loja);
    }
    public Opcoes opcoes() {
        return new Opcoes(
                Arrays.stream(Tamanho.values()).map(t -> new OpcaoTamanho(t, t.getNome(), t.getAjuste(), t.getFatorInsumo())).toList(),
                Arrays.stream(Extra.values()).map(e -> new OpcaoExtra(e, e.getNome(), e.getPreco())).toList(),
                Arrays.stream(FormaPagamento.values()).map(f -> new OpcaoPagamento(f, f.getNome(), f.getParcelasMax())).toList());
    }
}
