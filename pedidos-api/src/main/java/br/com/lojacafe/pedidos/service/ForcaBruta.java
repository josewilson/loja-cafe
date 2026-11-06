package br.com.lojacafe.pedidos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Freio contra força bruta: conta falhas por chave (ex.: IP + e-mail) numa janela de tempo. */
@Component
public class ForcaBruta {

    // ponytail: o contador fica na memória desta instância; com várias instâncias da API, trocar por contador compartilhado (Redis/banco).
    private final Map<String, Deque<Instant>> tentativas = new ConcurrentHashMap<>();

    /** Já houve `max` ou mais falhas da chave dentro da janela? */
    public boolean estourou(String chave, int max, Duration janela) {
        Deque<Instant> fila = tentativas.get(chave);
        if (fila == null) {
            return false;
        }
        synchronized (fila) {
            descartarVelhas(fila, janela);
            return fila.size() >= max;
        }
    }

    public void registrarFalha(String chave) {
        Deque<Instant> fila = tentativas.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (fila) {
            fila.addLast(Instant.now());
        }
    }

    public void zerar(String chave) {
        tentativas.remove(chave);
    }

    /** Esquece quem já acalmou (o mapa não cresce para sempre). */
    public void faxina() {
        tentativas.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                descartarVelhas(e.getValue(), Duration.ofHours(1));
                return e.getValue().isEmpty();
            }
        });
    }

    private static void descartarVelhas(Deque<Instant> fila, Duration janela) {
        Instant limite = Instant.now().minus(janela);
        while (!fila.isEmpty() && fila.peekFirst().isBefore(limite)) {
            fila.removeFirst();
        }
    }
}
