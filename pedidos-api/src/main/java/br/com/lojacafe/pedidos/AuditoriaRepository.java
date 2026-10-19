package br.com.lojacafe.pedidos;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
    List<Auditoria> findAllByOrderByIdDesc(Pageable limite);

    List<Auditoria> findByLojaIdOrderByIdDesc(Long lojaId, Pageable limite);
}
