package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.Sessao;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessaoRepository extends JpaRepository<Sessao, String> {

    @Modifying
    @Query("delete from Sessao s where s.expiraEm < :agora")
    int apagarVencidas(Instant agora);

    /** Desativar uma conta derruba as sessões dela na hora. */
    @Modifying
    @Query("delete from Sessao s where s.usuario.id = :usuarioId")
    int apagarDoUsuario(Long usuarioId);
}
