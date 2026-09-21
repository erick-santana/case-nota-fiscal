package br.com.itau.geradornotafiscal.adapter.out.financeiro;

import br.com.itau.geradornotafiscal.application.port.out.FinanceiroNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import org.springframework.stereotype.Component;

@Component
public class FinanceiroAdapter implements FinanceiroNotificacaoPort {

    @Override
    public void lancar(NotaFiscal notaFiscal) {
        try {
            // Simula o envio da nota fiscal para o contas a receber
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegracaoDownstreamException("financeiro", notaFiscal.getIdNotaFiscal(), e);
        }
    }
}
