package br.com.lojacafe.pedidos.controller;

import br.com.lojacafe.pedidos.config.AuthInterceptor;
import br.com.lojacafe.pedidos.service.AuthService;
import br.com.lojacafe.pedidos.service.AuthService.Token;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Login, logout e nova senha (specs/api.md). As regras estão em {@link AuthService}; a checagem de toda rota, em {@link AuthInterceptor}. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    public record Credenciais(@NotBlank String email, @NotBlank String senha) {}
    public record NovaSenha(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String senha) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/entrar")
    public Token entrar(@Valid @RequestBody Credenciais c, HttpServletRequest req) {
        return auth.entrar(c.email(), c.senha(), req.getRemoteAddr());
    }

    @PostMapping("/redefinir-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redefinirSenha(@Valid @RequestBody NovaSenha corpo, HttpServletRequest req) {
        auth.redefinirSenha(corpo.token(), corpo.senha(), req.getRemoteAddr());
    }

    @PostMapping("/sair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sair(@RequestAttribute(AuthInterceptor.TOKEN) String tokenHash) {
        auth.sair(tokenHash);
    }
}
