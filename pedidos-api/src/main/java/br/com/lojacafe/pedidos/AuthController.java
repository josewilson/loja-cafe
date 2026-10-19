package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Cadastro e login (specs/api.md). Devolve um token aleatório; o banco guarda só o hash dele. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    public record Registro(@NotBlank @Size(max = 60) String nome,
                           @NotBlank @Email @Size(max = 120) String email,
                           @NotBlank @Size(min = 8, max = 72) String senha) {}
    public record Credenciais(@NotBlank String email, @NotBlank String senha) {}
    public record Token(String token, String nome) {}

    private static final Duration VALIDADE = Duration.ofHours(24);

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom aleatorio = new SecureRandom();
    // Hash de uma senha qualquer: e-mail inexistente gasta o mesmo tempo que senha errada.
    private final String hashFalso = bcrypt.encode("senha-que-ninguem-tem");

    public AuthController(UsuarioRepository usuarios, SessaoRepository sessoes) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
    }

    @PostMapping("/registrar")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Token registrar(@Valid @RequestBody Registro r) {
        String email = r.email().trim().toLowerCase();
        if (usuarios.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este e-mail já está cadastrado");
        }
        return abrirSessao(usuarios.save(new Usuario(r.nome(), email, bcrypt.encode(r.senha()))));
    }

    @PostMapping("/entrar")
    @Transactional
    public Token entrar(@Valid @RequestBody Credenciais c) {
        Usuario usuario = usuarios.findByEmail(c.email().trim().toLowerCase()).orElse(null);
        boolean confere = bcrypt.matches(c.senha(), usuario != null ? usuario.getSenhaHash() : hashFalso);
        if (usuario == null || !confere) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }
        return abrirSessao(usuario);
    }

    private Token abrirSessao(Usuario usuario) {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessoes.save(new Sessao(Autenticacao.hash(token), usuario, Instant.now().plus(VALIDADE)));
        return new Token(token, usuario.getNome());
    }
}
