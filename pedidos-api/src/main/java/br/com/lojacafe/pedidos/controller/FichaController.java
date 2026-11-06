package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.Extra;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.service.FichaService;
import br.com.lojacafe.pedidos.service.FichaService.Ficha;
import br.com.lojacafe.pedidos.service.FichaService.FichaVista;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Fichas técnicas: quanto de cada insumo gasta 1 unidade (tamanho Médio) de um produto ou de um extra. Só o ADMIN edita. Regras em {@link FichaService}. */
@RestController
@RequestMapping("/fichas")
public class FichaController {

    private final FichaService fichas;

    public FichaController(FichaService fichas) {
        this.fichas = fichas;
    }

    @GetMapping("/produtos/{id}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public FichaVista produto(@PathVariable Long id) {
        return fichas.produto(id);
    }

    @PutMapping("/produtos/{id}")
    @Papeis(Papel.ADMIN)
    public FichaVista salvarProduto(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @PathVariable Long id, @Valid @RequestBody Ficha ficha) {
        return fichas.salvarProduto(quem, id, ficha);
    }

    @GetMapping("/extras/{codigo}")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public FichaVista extra(@PathVariable Extra codigo) {
        return fichas.extra(codigo);
    }

    @PutMapping("/extras/{codigo}")
    @Papeis(Papel.ADMIN)
    public FichaVista salvarExtra(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @PathVariable Extra codigo, @Valid @RequestBody Ficha ficha) {
        return fichas.salvarExtra(quem, codigo, ficha);
    }
}
