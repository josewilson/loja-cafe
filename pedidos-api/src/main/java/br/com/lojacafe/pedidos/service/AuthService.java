package br.com.lojacafe.pedidos.service;

import br.com.lojacafe.pedidos.model.Loja;
import br.com.lojacafe.pedidos.model.Papel;
import br.com.lojacafe.pedidos.model.Sessao;
import br.com.lojacafe.pedidos.model.Usuario;
import br.com.lojacafe.pedidos.repository.AuditoriaRepository;
import br.com.lojacafe.pedidos.repository.SessaoRepository;
import br.com.lojacafe.pedidos.repository.UsuarioRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login, sessão, nova senha e a faxina de sessões (specs/api.md). Devolve um token aleatório; o banco guarda só o hash dele.
 * O freio contra força bruta está em {@link ForcaBruta}; contas são criadas em {@link UsuarioService}.
 */
@Service
public class AuthService {

    public record Token(String token, String nome, Papel papel, Long lojaId, String lojaNome) {}

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration VALIDADE = Duration.ofHours(24);
    // Senha errada: 5 vezes por 15 min para o mesmo IP + e-mail.
    private static final int MAX_FALHAS = 5;
    private static final Duration JANELA_FALHAS = Duration.ofMinutes(15);

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final AuditoriaRepository auditor;
    private final BCryptPasswordEncoder bcrypt;
    private final ForcaBruta freio;
    // Hash de uma senha qualquer: e-mail inexistente gasta o mesmo tempo que senha errada.
    private final String hashFalso;

    public AuthService(UsuarioRepository usuarios, SessaoRepository sessoes, AuditoriaRepository auditor,
                       BCryptPasswordEncoder bcrypt, ForcaBruta freio) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.auditor = auditor;
        this.bcrypt = bcrypt;
        this.freio = freio;
        this.hashFalso = bcrypt.encode("senha-que-ninguem-tem");
    }

    // noRollbackFor: a falha de login também é gravada na auditoria, mesmo respondendo com erro.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Token entrar(String emailInformado, String senha, String ip) {
        String email = emailInformado.trim().toLowerCase();
        String chave = "login:" + ip + "|" + email;
        if (freio.estourou(chave, MAX_FALHAS, JANELA_FALHAS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas. Aguarde alguns minutos e tente de novo"); // antes do BCrypt: quem é atacado não gasta CPU
        }
        Usuario usuario = usuarios.findByEmail(email).orElse(null);
        boolean confere = bcrypt.matches(senha, usuario != null ? usuario.getSenhaHash() : hashFalso);
        if (usuario == null || !confere || !usuario.isAtivo()) {
            freio.registrarFalha(chave);
            auditor.registrar(usuario, usuario == null ? null : usuario.getLojaId(), "LOGIN_FALHOU", "Usuario",
                    usuario == null ? null : usuario.getId(), "email " + email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }
        freio.zerar(chave);
        auditor.registrar(usuario, usuario.getLojaId(), "LOGIN", "Usuario", usuario.getId(), null);
        return abrirSessao(usuario);
    }

    /** Fase 8b: usa o link gerado pelo gerente. O token vale uma vez; a senha nova derruba as sessões abertas da conta. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void redefinirSenha(String token, String senhaNova, String ip) {
        String chave = "redefinir:" + ip;
        if (freio.estourou(chave, MAX_FALHAS, JANELA_FALHAS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos e tente de novo");
        }
        Usuario usuario = usuarios.findByRedefinicaoHash(Acesso.hash(token)).filter(Usuario::redefinicaoValida).orElse(null);
        if (usuario == null || !usuario.isAtivo()) {
            freio.registrarFalha(chave);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link inválido ou vencido. Peça um novo ao gerente");
        }
        usuario.redefinirSenha(bcrypt.encode(senhaNova));
        usuarios.save(usuario);
        sessoes.apagarDoUsuario(usuario.getId());
        auditor.registrar(usuario, usuario.getLojaId(), "SENHA_REDEFINIDA", "Usuario", usuario.getId(), null);
    }

    @Transactional
    public void sair(String tokenHash) {
        sessoes.deleteById(tokenHash);
    }

    /** Dono de uma sessão aberta: vazio se o token não existe, venceu ou a conta foi desativada. */
    @Transactional(readOnly = true)
    public Optional<Usuario> usuarioDaSessao(String tokenHash) {
        return sessoes.findById(tokenHash)
                .filter(s -> s.getExpiraEm().isAfter(Instant.now()))
                .map(Sessao::getUsuario)
                .filter(Usuario::isAtivo);
    }

    private Token abrirSessao(Usuario usuario) {
        String token = Acesso.tokenAleatorio();
        sessoes.save(new Sessao(Acesso.hash(token), usuario, Instant.now().plus(VALIDADE)));
        Loja loja = usuario.getLoja();
        return new Token(token, usuario.getNome(), usuario.getPapel(), usuario.getLojaId(), loja == null ? null : loja.getNome());
    }

    /** Faxina de hora em hora: sessões vencidas saem do banco e o freio esquece quem já acalmou. */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void faxina() {
        int apagadas = sessoes.apagarVencidas(Instant.now());
        freio.faxina();
        if (apagadas > 0) {
            log.info("{} sessão(ões) vencida(s) removida(s)", apagadas);
        }
    }
}
