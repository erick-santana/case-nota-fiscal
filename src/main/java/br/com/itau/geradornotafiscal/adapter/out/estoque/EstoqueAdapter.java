package br.com.itau.geradornotafiscal.adapter.out.estoque;

import br.com.itau.geradornotafiscal.application.port.out.EstoqueNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import org.springframework.stereotype.Component;

@Component
public class EstoqueAdapter implements EstoqueNotificacaoPort {

    @Override
    public void notificar(NotaFiscal notaFiscal) {
        try {
            // Simula envio de nota fiscal para baixa de estoque
            Thread.sleep(380);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegracaoDownstreamException("estoque", notaFiscal.getIdNotaFiscal(), e);
        }
    }
}
