package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Registro de auditoria (specs/api.md). Regras em {@link AuditoriaService}. */
@RestController
@RequestMapping("/auditoria")
public class AuditoriaController {

    private final AuditoriaService auditoria;

    public AuditoriaController(AuditoriaService auditoria) {
        this.auditoria = auditoria;
    }

    /** Do mais novo ao mais antigo (padrão 100, de 1 a 500). O gerente vê só a sua loja; o ADMIN vê tudo (ou filtra por lojaId). */
    @GetMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<Auditoria> listar(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @RequestParam(defaultValue = "100") int limite, @RequestParam(required = false) Long lojaId) {
        return auditoria.listar(quem, limite, lojaId);
    }
}
