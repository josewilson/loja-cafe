package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Cardápio e opções que o front desenha na tela (specs/api.md). Regras em {@link CardapioService}. */
@RestController
public class CardapioController {

    private final CardapioService cardapio;

    public CardapioController(CardapioService cardapio) {
        this.cardapio = cardapio;
    }

    /** Cardápio da rede com o estoque da loja do usuário. O ADMIN (sem loja) escolhe com ?lojaId=. */
    @GetMapping("/produtos")
    public List<ProdutoComEstoque> produtos(@RequestAttribute(AuthInterceptor.USUARIO) Usuario usuario,
                                                                                               @RequestParam(required = false) Long lojaId) {
        return cardapio.produtos(usuario, lojaId);
    }

    @GetMapping("/opcoes")
    public CardapioService.Opcoes opcoes() {
        return cardapio.opcoes();
    }
}
