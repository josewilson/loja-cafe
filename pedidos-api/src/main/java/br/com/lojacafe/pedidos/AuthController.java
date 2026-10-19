package br.com.lojacafe.pedidos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Login e logout (specs/api.md). Devolve um token aleatório; o banco guarda só o hash dele. Contas são criadas em /usuarios. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    public record Credenciais(@NotBlank String email, @NotBlank String senha) {}
    public record Token(String token, String nome, Papel papel, Long lojaId, String lojaNome) {}

    private static final Duration VALIDADE = Duration.ofHours(24);
    // Senha errada: 5 vezes por 15 min para o mesmo IP + e-mail.
    private static final int MAX_FALHAS = 5;
    private static final Duration JANELA_FALHAS = Duration.ofMinutes(15);

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final LimiteTentativas limite;
    private final Auditor auditor;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom aleatorio = new SecureRandom();
    // Hash de uma senha qualquer: e-mail inexistente gasta o mesmo tempo que senha errada.
    private final String hashFalso = bcrypt.encode("senha-que-ninguem-tem");

    public AuthController(UsuarioRepository usuarios, SessaoRepository sessoes, LimiteTentativas limite, Auditor auditor) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.limite = limite;
        this.auditor = auditor;
    }

    // noRollbackFor: a falha de login também é gravada na auditoria, mesmo respondendo com erro.
    @PostMapping("/entrar")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Token entrar(@Valid @RequestBody Credenciais c, HttpServletRequest req) {
        String email = c.email().trim().toLowerCase();
        String chave = "login:" + req.getRemoteAddr() + "|" + email;
        if (limite.estourou(chave, MAX_FALHAS, JANELA_FALHAS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas. Aguarde alguns minutos e tente de novo"); // antes do BCrypt: quem é atacado não gasta CPU
        }
        Usuario usuario = usuarios.findByEmail(email).orElse(null);
        boolean confere = bcrypt.matches(c.senha(), usuario != null ? usuario.getSenhaHash() : hashFalso);
        if (usuario == null || !confere || !usuario.isAtivo()) {
            limite.registrar(chave);
            auditor.registrar(usuario, usuario == null ? null : usuario.getLojaId(), "LOGIN_FALHOU", "Usuario",
                    usuario == null ? null : usuario.getId(), "email " + email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }
        limite.zerar(chave);
        auditor.registrar(usuario, usuario.getLojaId(), "LOGIN", "Usuario", usuario.getId(), null);
        return abrirSessao(usuario);
    }

    @PostMapping("/sair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sair(@RequestAttribute(Autenticacao.TOKEN) String tokenHash) {
        sessoes.deleteById(tokenHash);
    }

    private Token abrirSessao(Usuario usuario) {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessoes.save(new Sessao(Autenticacao.hash(token), usuario, Instant.now().plus(VALIDADE)));
        Loja loja = usuario.getLoja();
        return new Token(token, usuario.getNome(), usuario.getPapel(), usuario.getLojaId(), loja == null ? null : loja.getNome());
    }
}
