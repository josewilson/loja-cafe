package br.com.lojacafe.pedidos;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Tudo de acesso num lugar só (specs/api.md): login e logout, o porteiro de toda rota, o CORS, os cabeçalhos de segurança,
 * o freio contra força bruta e a faxina de sessões. Devolve um token aleatório; o banco guarda só o hash dele.
 * Contas são criadas em /usuarios.
 *
 * O porteiro exige "Authorization: Bearer <token>" de uma conta ativa em toda rota (menos /auth/entrar, /opcoes, /saude e /error),
 * confere o papel pedido em @Papeis e entrega o usuário ao controller.
 */
@RestController
@RequestMapping("/auth")
public class AuthController implements HandlerInterceptor, WebMvcConfigurer {

    /** Quem pode chamar este endpoint. Sem a anotação, qualquer usuário logado pode. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Papeis {
        Papel[] value();
    }

    public record Credenciais(@NotBlank String email, @NotBlank String senha) {}
    public record Token(String token, String nome, Papel papel, Long lojaId, String lojaNome) {}

    public static final String USUARIO = "usuario";
    public static final String TOKEN = "tokenHash";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Duration VALIDADE = Duration.ofHours(24);
    // Senha errada: 5 vezes por 15 min para o mesmo IP + e-mail.
    private static final int MAX_FALHAS = 5;
    private static final Duration JANELA_FALHAS = Duration.ofMinutes(15);

    // Em outro ambiente: lojacafe.cors.origem=https://meu-site (propriedade ou variável de ambiente).
    @Value("${lojacafe.cors.origem:http://localhost:5173}")
    private String origem;

    private final UsuarioRepository usuarios;
    private final SessaoRepository sessoes;
    private final AuditoriaRepository auditor;
    private final BCryptPasswordEncoder bcrypt;
    private static final SecureRandom ALEATORIO = new SecureRandom();
    // Hash de uma senha qualquer: e-mail inexistente gasta o mesmo tempo que senha errada.
    private final String hashFalso;
    // ponytail: o contador de tentativas fica na memória desta instância; com várias instâncias da API, trocar por contador compartilhado (Redis/banco).
    private final Map<String, Deque<Instant>> tentativas = new ConcurrentHashMap<>();

    public AuthController(UsuarioRepository usuarios, SessaoRepository sessoes, AuditoriaRepository auditor, BCryptPasswordEncoder bcrypt) {
        this.bcrypt = bcrypt;
        this.hashFalso = bcrypt.encode("senha-que-ninguem-tem");
        this.usuarios = usuarios;
        this.sessoes = sessoes;
        this.auditor = auditor;
    }

    // ---------- login e logout ----------

    // noRollbackFor: a falha de login também é gravada na auditoria, mesmo respondendo com erro.
    @PostMapping("/entrar")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Token entrar(@Valid @RequestBody Credenciais c, HttpServletRequest req) {
        String email = c.email().trim().toLowerCase();
        String chave = "login:" + req.getRemoteAddr() + "|" + email;
        if (estourou(chave, MAX_FALHAS, JANELA_FALHAS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas. Aguarde alguns minutos e tente de novo"); // antes do BCrypt: quem é atacado não gasta CPU
        }
        Usuario usuario = usuarios.findByEmail(email).orElse(null);
        boolean confere = bcrypt.matches(c.senha(), usuario != null ? usuario.getSenhaHash() : hashFalso);
        if (usuario == null || !confere || !usuario.isAtivo()) {
            registrarFalha(chave);
            auditor.registrar(usuario, usuario == null ? null : usuario.getLojaId(), "LOGIN_FALHOU", "Usuario",
                    usuario == null ? null : usuario.getId(), "email " + email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos");
        }
        tentativas.remove(chave);
        auditor.registrar(usuario, usuario.getLojaId(), "LOGIN", "Usuario", usuario.getId(), null);
        return abrirSessao(usuario);
    }

    public record NovaSenha(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String senha) {}

    /** Fase 8b: usa o link gerado pelo gerente. O token vale uma vez; a senha nova derruba as sessões abertas da conta. */
    @PostMapping("/redefinir-senha")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void redefinirSenha(@Valid @RequestBody NovaSenha corpo, HttpServletRequest req) {
        String chave = "redefinir:" + req.getRemoteAddr();
        if (estourou(chave, MAX_FALHAS, JANELA_FALHAS)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos e tente de novo");
        }
        Usuario usuario = usuarios.findByRedefinicaoHash(hash(corpo.token())).filter(Usuario::redefinicaoValida).orElse(null);
        if (usuario == null || !usuario.isAtivo()) {
            registrarFalha(chave);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Link inválido ou vencido. Peça um novo ao gerente");
        }
        usuario.redefinirSenha(bcrypt.encode(corpo.senha()));
        usuarios.save(usuario);
        sessoes.apagarDoUsuario(usuario.getId());
        auditor.registrar(usuario, usuario.getLojaId(), "SENHA_REDEFINIDA", "Usuario", usuario.getId(), null);
    }

    @PostMapping("/sair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sair(@RequestAttribute(TOKEN) String tokenHash) {
        sessoes.deleteById(tokenHash);
    }

    private Token abrirSessao(Usuario usuario) {
        String token = tokenAleatorio();
        sessoes.save(new Sessao(hash(token), usuario, Instant.now().plus(VALIDADE)));
        Loja loja = usuario.getLoja();
        return new Token(token, usuario.getNome(), usuario.getPapel(), usuario.getLojaId(), loja == null ? null : loja.getNome());
    }

    // ---------- ajudas que os outros controllers usam ----------

    /** Token de 256 bits (sessão e link de nova senha). */
    public static String tokenAleatorio() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** O banco guarda só o hash do token. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 existe em todo JDK
        }
    }

    /**
     * Qual loja a requisição enxerga. Funcionário de loja: sempre a dele (pedir outra é 403).
     * ADMIN: a que ele escolher pela query; sem escolha, devolve nulo (= todas).
     */
    public static Long escopo(Usuario usuario, Long lojaPedida) {
        if (usuario.getPapel() == Papel.ADMIN) {
            return lojaPedida;
        }
        if (lojaPedida != null && !lojaPedida.equals(usuario.getLojaId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só acessa a sua loja");
        }
        return usuario.getLojaId();
    }

    // ---------- o porteiro (HandlerInterceptor) ----------

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true; // pré-voo do CORS não leva token
        }
        String cabecalho = req.getHeader("Authorization");
        if (cabecalho != null && cabecalho.startsWith("Bearer ")) {
            String tokenHash = hash(cabecalho.substring(7));
            var sessao = sessoes.findById(tokenHash);
            if (sessao.isPresent() && sessao.get().getExpiraEm().isAfter(Instant.now()) && sessao.get().getUsuario().isAtivo()) {
                Usuario usuario = sessao.get().getUsuario();
                if (handler instanceof HandlerMethod metodo) {
                    Papeis exigidos = metodo.getMethodAnnotation(Papeis.class);
                    if (exigidos != null && !Arrays.asList(exigidos.value()).contains(usuario.getPapel())) {
                        res.sendError(HttpServletResponse.SC_FORBIDDEN, "Seu papel não permite isto");
                        return false;
                    }
                }
                req.setAttribute(USUARIO, usuario);
                req.setAttribute(TOKEN, tokenHash);
                return true;
            }
        }
        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Faça login para continuar");
        return false;
    }

    // ---------- CORS e cabeçalhos de segurança ----------

    /** Libera o front (Vite, porta 5173). */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origem).allowedMethods("GET", "POST", "PUT").allowedHeaders("Authorization", "Content-Type");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Tudo exige login, menos o próprio login, as opções (tamanhos/extras/pagamentos), a saúde da API e a página de erro.
        registry.addInterceptor(this).addPathPatterns("/**").excludePathPatterns("/auth/entrar", "/auth/redefinir-senha", "/opcoes", "/saude", "/error");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("Referrer-Policy", "no-referrer");
                res.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                return true;
            }
        });
    }

    // ---------- freio contra força bruta e faxina ----------

    /** Já houve `max` ou mais ocorrências da chave dentro da janela? */
    private boolean estourou(String chave, int max, Duration janela) {
        Deque<Instant> fila = tentativas.get(chave);
        if (fila == null) {
            return false;
        }
        synchronized (fila) {
            descartarVelhas(fila, janela);
            return fila.size() >= max;
        }
    }

    private void registrarFalha(String chave) {
        Deque<Instant> fila = tentativas.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(Instant.now());
        }
    }

    private static void descartarVelhas(Deque<Instant> fila, Duration janela) {
        Instant limite = Instant.now().minus(janela);
        while (!fila.isEmpty() && fila.peekFirst().isBefore(limite)) {
            fila.removeFirst();
        }
    }

    /** Faxina de hora em hora: sessões vencidas saem do banco e o contador esquece quem já acalmou (o mapa não cresce para sempre). */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void faxina() {
        int apagadas = sessoes.apagarVencidas(Instant.now());
        tentativas.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                descartarVelhas(e.getValue(), Duration.ofHours(1));
                return e.getValue().isEmpty();
            }
        });
        if (apagadas > 0) {
            log.info("{} sessão(ões) vencida(s) removida(s)", apagadas);
        }
    }
}
