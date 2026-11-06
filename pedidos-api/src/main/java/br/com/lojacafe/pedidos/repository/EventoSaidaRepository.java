package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.EventoSaida;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EventoSaidaRepository extends JpaRepository<EventoSaida, Long> {

    /** Os próximos a enviar, em ordem, com trava: duas instâncias da API não pegam os mesmos eventos. */
    @Query(value = "select * from evento_saida where enviado_em is null order by id limit 100 for update skip locked", nativeQuery = true)
    List<EventoSaida> pendentes();

    @Modifying
    @Query("delete from EventoSaida e where e.enviadoEm < :limite")
    int apagarEnviadosAntesDe(Instant limite);
}
