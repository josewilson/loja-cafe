package br.com.lojacafe.pedidos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
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
    // Senha errada: 5 vezes por 15 min para o mesmo IP + e-mail. Contas criadas: limite por hora e por IP (configurável).
    private static final int MAX_FALHAS = 5;
    private static final Duration JANELA_FALHAS = Duration.ofMinutes(15);

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final LimiteTentativas limite;
    private final int maxRegistrosPorHora;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom aleatorio = new SecureRandom();
    // Hash de uma senha qualquer: e-mail inexistente gasta o mesmo tempo que senha errada.
    private final String hashFalso = bcrypt.encode("senha-que-ninguem-tem");

    public AuthController(UsuarioRepository usuarios, SessaoRepository sessoes, LimiteTentativas limite,
                          @Value("${lojacafe.limite.registros-por-hora:10}") int maxRegistrosPorHora) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.limite = limite;
        this.maxRegistrosPorHora = maxRegistrosPorHora;
    }

    @PostMapping("/registrar")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Token registrar(@Valid @RequestBody Registro r, HttpServletRequest req) {
        String chaveIp = "registro:" + req.getRemoteAddr();
        if (limite.estourou(chaveIp, maxRegistrosPorHora, Duration.ofHours(1))) {
            throw muitasTentativas();
        }
        String email = r.email().trim().toLowerCase();
        if (usuarios.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este e-mail já está cadastrado");
        }
        limite.registrar(chaveIp);
        return abrirSessao(usuarios.save(new Usuario(r.nome(), email, bcrypt.encode(r.senha()))));
    }

    @PostMapping("/entrar")
    @Transactional
    public Token entrar(@Valid @RequestBody Credenciais c, HttpServletRequest req) {
        String email = c.email().trim().toLowerCase();
        String chave = "login:" + req.getRemoteAddr() + "|" + email;
        if (limite.estourou(chave, MAX_FALHAS, JANELA_FALHAS)) {
            throw muitasTentativas(); // antes do BCrypt: quem está sendo atacado não gasta CPU
        }
        Usuario usuario = usuarios.findByEmail(email).orElse(null);
        boolean confere = bcrypt.matches(c.senha(), usuario != null ? usuario.getSenhaHash() : hashFalso);
        if (usuario == null || !confere) {
            limite.registrar(chave);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }
        limite.zerar(chave);
        return abrirSessao(usuario);
    }

    private static ResponseStatusException muitasTentativas() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos e tente de novo");
    }

    private Token abrirSessao(Usuario usuario) {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessoes.save(new Sessao(Autenticacao.hash(token), usuario, Instant.now().plus(VALIDADE)));
        return new Token(token, usuario.getNome());
    }
}
