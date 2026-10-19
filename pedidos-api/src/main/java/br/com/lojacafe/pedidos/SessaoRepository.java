package br.com.lojacafe.pedidos;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SessaoRepository extends JpaRepository<Sessao, String> {

    @Modifying
    @Query("delete from Sessao s where s.expiraEm < :agora")
    int apagarVencidas(Instant agora);
}
