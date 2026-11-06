package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Caixa da loja por turno (specs/api.md): resumo do turno aberto, fechamento e histórico. Regras em {@link CaixaService}. */
@RestController
@RequestMapping("/caixa")
public class CaixaController {

    private final CaixaService caixa;

    public CaixaController(CaixaService caixa) {
        this.caixa = caixa;
    }

    @GetMapping("/resumo")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public CaixaService.ResumoCaixa resumo(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @RequestParam(required = false) Long lojaId) {
        return caixa.resumo(usuario, lojaId);
    }

    @PostMapping("/fechamentos")
    @Papeis(Papel.GERENTE)
    @ResponseStatus(HttpStatus.CREATED)
    public CaixaService.FechamentoVista fechar(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @Valid @RequestBody CaixaService.Fechar corpo) {
        return caixa.fechar(usuario, corpo);
    }

    /** Histórico, do mais novo ao mais antigo (padrão 50, de 1 a 200). */
    @GetMapping("/fechamentos")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<CaixaService.FechamentoVista> historico(@RequestAttribute(Porteiro.USUARIO) Usuario usuario, @RequestParam(defaultValue = "50") int limite,
                                                        @RequestParam(required = false) Long lojaId) {
        return caixa.historico(usuario, limite, lojaId);
    }
}
