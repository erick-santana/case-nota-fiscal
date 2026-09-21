package br.com.itau.geradornotafiscal.application;

import br.com.itau.geradornotafiscal.model.NotaFiscal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Agrega as falhas das quatro notificações concorrentes (D-02): uma falha não impede
 * que as outras rodem, mas todas as causas originais são preservadas para diagnóstico.
 */
public class NotificacoesParcialmenteFalharamException extends RuntimeException {

    private final transient List<Throwable> causas;

    private NotificacoesParcialmenteFalharamException(String idNotaFiscal, List<Throwable> causas, int totalNotificacoes) {
        super("Falha ao notificar integrações downstream para a nota fiscal %s: %d de %d falharam"
                .formatted(idNotaFiscal, causas.size(), totalNotificacoes), causas.isEmpty() ? null : causas.get(0));
        this.causas = List.copyOf(causas);
    }

    public static NotificacoesParcialmenteFalharamException de(NotaFiscal notaFiscal, CompletableFuture<?>[] notificacoes) {
        List<Throwable> causas = new ArrayList<>();
        for (CompletableFuture<?> notificacao : notificacoes) {
            if (notificacao.isCompletedExceptionally()) {
                causas.add(causaRaiz(notificacao));
            }
        }
        return new NotificacoesParcialmenteFalharamException(notaFiscal.getIdNotaFiscal(), causas, notificacoes.length);
    }

    private static Throwable causaRaiz(CompletableFuture<?> notificacaoFalha) {
        try {
            notificacaoFalha.join();
            throw new IllegalStateException("esperava-se uma falha em " + notificacaoFalha);
        } catch (CompletionException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    public List<Throwable> getCausas() {
        return causas;
    }
}
