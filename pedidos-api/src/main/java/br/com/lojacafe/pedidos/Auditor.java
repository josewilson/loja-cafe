package br.com.lojacafe.pedidos;

import org.springframework.stereotype.Component;

/** Anota na auditoria o que aconteceu. Roda na mesma transação da ação: se ela desfaz, o registro some junto. */
@Component
public class Auditor {

    private final AuditoriaRepository repository;

    public Auditor(AuditoriaRepository repository) {
        this.repository = repository;
    }

    /** `quem` pode ser nulo (ex.: login de um e-mail que não existe). */
    public void registrar(Usuario quem, Long lojaId, String acao, String entidade, Object entidadeId, String detalhe) {
        repository.save(new Auditoria(quem == null ? null : quem.getId(), lojaId, acao, entidade,
                entidadeId == null ? null : String.valueOf(entidadeId), detalhe));
    }
}
