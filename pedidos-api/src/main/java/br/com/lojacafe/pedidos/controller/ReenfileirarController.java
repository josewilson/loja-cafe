package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.config.AuthInterceptor.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import org.springframework.web.bind.annotation.*;

/** Reenvia ao Pagamentos os pedidos que ficaram aguardando (specs/api.md). Regras em {@link ReenfileirarService}. */
@RestController
public class ReenfileirarController {

    private final ReenfileirarService reenfileirar;

    public ReenfileirarController(ReenfileirarService reenfileirar) {
        this.reenfileirar = reenfileirar;
    }

    @PostMapping("/pedidos/reenfileirar")
    @Papeis(Papel.ADMIN)
    public ReenfileirarService.Resultado reenfileirar(@RequestAttribute(AuthInterceptor.USUARIO) Usuario quem, @RequestParam(defaultValue = "10") int minutos) {
        return reenfileirar.reenfileirar(quem, minutos);
    }
}
