package br.com.lojacafe.pedidos.repository;

import br.com.lojacafe.pedidos.model.Auditoria;
import br.com.lojacafe.pedidos.model.Usuario;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
    List<Auditoria> findAllByOrderByIdDesc(Pageable limite);

    List<Auditoria> findByLojaIdOrderByIdDesc(Long lojaId, Pageable limite);

    /**
     * Anota na auditoria o que aconteceu. Roda na mesma transação da ação: se ela desfaz, o registro some junto.
     * `quem` pode ser nulo (ex.: login de um e-mail que não existe).
     */
    default void registrar(Usuario quem, Long lojaId, String acao, String entidade, Object entidadeId, String detalhe) {
        save(new Auditoria(quem == null ? null : quem.getId(), lojaId, acao, entidade,
                entidadeId == null ? null : String.valueOf(entidadeId), detalhe));
    }
}
