package br.com.lojacafe.pedidos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Contas da equipe (specs/api.md). Não existe cadastro público: quem cria é o ADMIN ou o GERENTE da loja. */
@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

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
    private final Auditor auditor;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public UsuarioController(UsuarioRepository usuarios, LojaRepository lojas, SessaoRepository sessoes, Auditor auditor) {
        this.usuarios = usuarios;
        this.lojas = lojas;
        this.sessoes = sessoes;
        this.auditor = auditor;
    }

    @GetMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    public List<UsuarioVista> listar(@RequestAttribute(Autenticacao.USUARIO) Usuario quem) {
        var lista = quem.getPapel() == Papel.ADMIN ? usuarios.findAllByOrderByIdAsc() : usuarios.findByLoja_IdOrderByIdAsc(quem.getLojaId());
        return lista.stream().map(UsuarioVista::de).toList();
    }

    @PostMapping
    @Papeis({Papel.ADMIN, Papel.GERENTE})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UsuarioVista criar(@RequestAttribute(Autenticacao.USUARIO) Usuario quem, @Valid @RequestBody NovoUsuario novo) {
        Loja loja = null;
        if (quem.getPapel() == Papel.GERENTE) {
            // Gerente só cria atendente e cozinha, e só na própria loja.
            if (novo.papel() != Papel.ATENDENTE && novo.papel() != Papel.COZINHA) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gerente só cria ATENDENTE e COZINHA");
            }
            Autenticacao.escopo(quem, novo.lojaId());
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
    public UsuarioVista definirAtivo(@RequestAttribute(Autenticacao.USUARIO) Usuario quem,
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
