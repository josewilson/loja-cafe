package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.Extra;
import br.com.lojacafe.pedidos.model.FichaExtra;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FichaExtraRepository extends JpaRepository<FichaExtra, FichaExtra.Id> {
    List<FichaExtra> findByIdExtra(Extra extra);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FichaExtra f where f.id.extra = :extra")
    int apagarDoExtra(Extra extra);
}
