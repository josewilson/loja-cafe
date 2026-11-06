package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Catálogo de insumos da rede, estoque por loja e alertas (specs/api.md, "Insumos e ficha técnica"). Regras em {@link InsumoService}. */
@RestController
public class InsumoController {

    private final InsumoService insumos;

    public InsumoController(InsumoService insumos) {
        this.insumos = insumos;
    }

    @GetMapping("/insumos")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<InsumoService.InsumoVista> listar(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @RequestParam(required = false) Long lojaId) {
        return insumos.listar(usuario, lojaId);
    }

    @PostMapping("/insumos")
    @Papeis(Papel.ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public InsumoService.InsumoBasico criar(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @Valid @RequestBody InsumoService.NovoInsumo novo) {
        return insumos.criar(quem, novo);
    }

    @PutMapping("/insumos/{id}")
    @Papeis(Papel.ADMIN)
    public InsumoService.InsumoBasico alterar(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @PathVariable Long id, @Valid @RequestBody InsumoService.AlterarInsumo dados) {
        return insumos.alterar(quem, id, dados);
    }

    /** A contagem do estoque de um insumo numa loja (e, se vier, o mínimo do alerta). O gerente só mexe na própria loja. */
    @PutMapping("/lojas/{lojaId}/insumos/{insumoId}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public InsumoService.EstoqueInsumoVista contar(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @PathVariable Long lojaId, @PathVariable Long insumoId,
                                                   @Valid @RequestBody InsumoService.Contagem corpo) {
        return insumos.contar(quem, lojaId, insumoId, corpo);
    }

    @GetMapping("/insumos/alertas")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<InsumoService.Alerta> alertas(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @RequestParam(required = false) Long lojaId) {
        return insumos.alertas(usuario, lojaId);
    }
}
