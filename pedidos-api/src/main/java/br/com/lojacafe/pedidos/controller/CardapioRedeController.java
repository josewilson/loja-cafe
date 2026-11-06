package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.config.AuthInterceptor.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Cardápio da rede (specs/api.md): só o ADMIN edita, porque o preço muda em todas as lojas. Regras em {@link CardapioRedeService}. */
@RestController
@RequestMapping("/cardapio")
public class CardapioRedeController {

    private final CardapioRedeService cardapio;

    public CardapioRedeController(CardapioRedeService cardapio) {
        this.cardapio = cardapio;
    }

    /** Todos os produtos, inclusive os desativados. */
    @GetMapping
    @Papeis(Papel.ADMIN)
    public List<CardapioRedeService.ProdutoVista> listar() {
        return cardapio.listar();
    }

    @PostMapping
    @Papeis(Papel.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public CardapioRedeService.ProdutoVista criar(@RequestAttribute(AuthInterceptor.USUARIO) Usuario quem, @Valid @RequestBody CardapioRedeService.DadosProduto dados) {
        return cardapio.criar(quem, dados);
    }

    @PutMapping("/{id}")
    @Papeis(Papel.ADMIN)
    public CardapioRedeService.ProdutoVista alterar(@RequestAttribute(AuthInterceptor.USUARIO) Usuario quem, @PathVariable Long id, @Valid @RequestBody CardapioRedeService.DadosProduto dados) {
        return cardapio.alterar(quem, id, dados);
    }
}
