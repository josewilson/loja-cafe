package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.controller.Porteiro.Papeis;
import br.com.lojacafe.pedidos.model.*;
import br.com.lojacafe.pedidos.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Contas da equipe (specs/api.md). Não existe cadastro público: quem cria é o ADMIN ou o GERENTE da loja. Regras em {@link UsuarioService}. */
@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService usuarios;

    public UsuarioController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<UsuarioService.UsuarioVista> listar(@RequestAttribute(Porteiro.USUARIO) Usuario quem) {
        return usuarios.listar(quem);
    }

    @PostMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioService.UsuarioVista criar(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @Valid @RequestBody UsuarioService.NovoUsuario novo) {
        return usuarios.criar(quem, novo);
    }

    @PutMapping("/{id}/ativo")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public UsuarioService.UsuarioVista definirAtivo(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @PathVariable Long id, @RequestBody UsuarioService.Ativo corpo) {
        return usuarios.definirAtivo(quem, id, corpo);
    }

    /** Fase 8b: gera o link de nova senha (vale 1 hora, uma vez). */
    @PostMapping("/{id}/redefinicao")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public UsuarioService.Redefinicao redefinicao(@RequestAttribute(Porteiro.USUARIO) Usuario quem, @PathVariable Long id) {
        return usuarios.redefinicao(quem, id);
    }
}
