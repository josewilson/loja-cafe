package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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
    public ReenfileirarService.Resultado reenfileirar(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @RequestParam(defaultValue = "10") int minutos) {
        return reenfileirar.reenfileirar(quem, minutos);
    }
}
