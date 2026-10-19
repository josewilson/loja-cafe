package br.com.lojacafe.pedidos;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsumoRepository extends JpaRepository<Insumo, Long> {
    List<Insumo> findAllByOrderByIdAsc();

    Optional<Insumo> findByNomeIgnoreCase(String nome);
}
