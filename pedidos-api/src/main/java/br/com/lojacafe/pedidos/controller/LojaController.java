package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.config.AuthInterceptor.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Lojas da rede e o estoque de cada uma (specs/api.md). Regras em {@link LojaService}. */
@RestController
public class LojaController {

    private final LojaService lojas;

    public LojaController(LojaService lojas) {
        this.lojas = lojas;
    }

    @GetMapping("/lojas")
    @Papeis(Papel.ADMIN)
    public List<LojaService.LojaVista> listar() {
        return lojas.listar();
    }

    @PostMapping("/lojas")
    @Papeis(Papel.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public LojaService.LojaVista criar(@RequestAttribute(AuthInterceptor.USUARIO) Usuario quem, @Valid @RequestBody LojaService.NovaLoja nova) {
        return lojas.criar(quem, nova);
    }

    /** Só os dados fiscais mudam aqui; o nome da loja não. Campo ausente = apagado. */
    @PutMapping("/lojas/{lojaId}")
    @Papeis(Papel.ADMIN)
    public LojaService.LojaVista alterarFiscal(@RequestAttribute(AuthInterceptor.USUARIO) Usuario quem, @PathVariable Long lojaId, @Valid @RequestBody LojaService.DadosFiscais dados) {
        return lojas.alterarFiscal(quem, lojaId, dados);
    }

    @PutMapping("/lojas/{lojaId}/estoque/{produtoId}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public LojaService.EstoqueVista ajustarEstoque(@RequestAttribute(AuthInterceptor.USUARIO) Usuario quem, @PathVariable Long lojaId, @PathVariable Long produtoId,
                                                   @Valid @RequestBody LojaService.NovaQuantidade corpo) {
        return lojas.ajustarEstoque(quem, lojaId, produtoId, corpo);
    }
}
