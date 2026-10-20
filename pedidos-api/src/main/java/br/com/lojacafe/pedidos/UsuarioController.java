package br.com.lojacafe.pedidos;

import br.com.lojacafe.pedidos.AuthController.Papeis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Contas da equipe (specs/api.md). Não existe cadastro público: quem cria é o ADMIN ou o GERENTE da loja. */
@RestController
@RequestMapping("/usuarios")
public class UsuarioController implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(UsuarioController.class);

    public record NovoUsuario(@NotBlank @Size(max = 60) String nome,
                              @NotBlank @Email @Size(max = 120) String email,
                              @NotBlank @Size(min = 8, max = 72) String senha,
                              @NotNull Papel papel,
                              Long lojaId) {}
    public record Ativo(boolean ativo) {}
    /** A vista pública de um usuário: nunca inclui a senha nem o hash. */
    public record UsuarioVista(Long id, String nome, String email, Papel papel, Long lojaId, boolean ativo) {
        static UsuarioVista de(Usuario u) {
            return new UsuarioVista(u.getId(), u.getNome(), u.getEmail(), u.getPapel(), u.getLojaId(), u.isAtivo());
        }
    }

    private final UsuarioRepository usuarios;
    private final LojaRepository lojas;
    private final SessaoRepository sessoes;
    private final AuditoriaRepository auditor;
    private final BCryptPasswordEncoder bcrypt;
    private final String adminEmail;
    private final String adminSenha;

    public UsuarioController(UsuarioRepository usuarios, LojaRepository lojas, SessaoRepository sessoes, AuditoriaRepository auditor,
                             BCryptPasswordEncoder bcrypt, @Value("${lojacafe.admin.email:}") String adminEmail,
                             @Value("${lojacafe.admin.senha:}") String adminSenha) {
        this.adminEmail = adminEmail;
        this.adminSenha = adminSenha;
        this.bcrypt = bcrypt;
        this.usuarios = usuarios;
        this.lojas = lojas;
        this.sessoes = sessoes;
        this.auditor = auditor;
    }

    /**
     * Primeira partida sem nenhum ADMIN: cria um com ADMIN_EMAIL e ADMIN_PASSWORD (infra/.env). Nunca há senha fixa no código.
     * Roda quando os beans terminam de nascer, ANTES de o servidor web aceitar a primeira requisição (um ApplicationRunner
     * rodaria depois: dava uma janela em que a API já respondia, mas o ADMIN ainda não existia).
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (usuarios.existsByPapel(Papel.ADMIN)) {
            return;
        }
        if (adminEmail.isBlank() || adminSenha.length() < 8) {
            log.warn("Nenhum ADMIN cadastrado. Defina ADMIN_EMAIL e ADMIN_PASSWORD (mínimo 8 caracteres) em infra/.env e reinicie.");
            return;
        }
        usuarios.save(new Usuario("Administrador", adminEmail, bcrypt.encode(adminSenha), Papel.ADMIN, null));
        log.info("ADMIN inicial criado: {}", adminEmail.trim().toLowerCase());
    }

    @GetMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<UsuarioVista> listar(@RequestAttribute(AuthController.USUARIO) Usuario quem) {
        var lista = quem.getPapel() == Papel.ADMIN ? usuarios.findAllByOrderByIdAsc() : usuarios.findByLoja_IdOrderByIdAsc(quem.getLojaId());
        return lista.stream().map(UsuarioVista::de).toList();
    }

    @PostMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UsuarioVista criar(@RequestAttribute(AuthController.USUARIO) Usuario quem, @Valid @RequestBody NovoUsuario novo) {
        Loja loja = null;
        if (quem.getPapel() == Papel.GERENTE) {
            // Gerente só cria atendente e cozinha, e só na própria loja.
            if (novo.papel() != Papel.ATENDENTE && novo.papel() != Papel.COZINHA) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gerente só cria ATENDENTE e COZINHA");
            }
            AuthController.escopo(quem, novo.lojaId());
            loja = quem.getLoja();
        } else if (novo.papel() != Papel.ADMIN) {
            if (novo.lojaId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe lojaId");
            }
            loja = lojas.findById(novo.lojaId()).filter(Loja::isAtiva)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "lojaId " + novo.lojaId() + " não existe"));
        }
        String email = novo.email().trim().toLowerCase();
        if (usuarios.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este e-mail já está cadastrado");
        }
        Usuario criado = usuarios.save(new Usuario(novo.nome(), email, bcrypt.encode(novo.senha()), novo.papel(), loja));
        auditor.registrar(quem, criado.getLojaId(), "USUARIO_CRIADO", "Usuario", criado.getId(),
                "papel " + criado.getPapel() + ", email " + email);
        return UsuarioVista.de(criado);
    }

    @PutMapping("/{id}/ativo")
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @Transactional
    public UsuarioVista definirAtivo(@RequestAttribute(AuthController.USUARIO) Usuario quem,
                                     @PathVariable Long id, @RequestBody Ativo corpo) {
        Usuario alvo = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não existe"));
        if (quem.getPapel() == Papel.GERENTE) {
            boolean daLoja = quem.getLojaId().equals(alvo.getLojaId());
            boolean papelOk = alvo.getPapel() == Papel.ATENDENTE || alvo.getPapel() == Papel.COZINHA;
            if (!daLoja || !papelOk) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você só gerencia atendentes e cozinha da sua loja");
            }
        }
        if (alvo.getId().equals(quem.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Você não pode desativar a si mesmo");
        }
        alvo.setAtivo(corpo.ativo());
        usuarios.save(alvo);
        if (!corpo.ativo()) {
            sessoes.apagarDoUsuario(alvo.getId()); // quem foi desligado cai na hora
        }
        auditor.registrar(quem, alvo.getLojaId(), "USUARIO_ATIVO_ALTERADO", "Usuario", alvo.getId(), "ativo " + corpo.ativo());
        return UsuarioVista.de(alvo);
    }
}
