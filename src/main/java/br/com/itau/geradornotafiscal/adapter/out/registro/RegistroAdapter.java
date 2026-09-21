package br.com.itau.geradornotafiscal.adapter.out.registro;

import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.application.port.out.RegistroNotificacaoPort;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import org.springframework.stereotype.Component;

@Component
public class RegistroAdapter implements RegistroNotificacaoPort {

    @Override
    public void notificar(NotaFiscal notaFiscal) {
        try {
            // Simula o registro da nota fiscal
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegracaoDownstreamException("registro", notaFiscal.getIdNotaFiscal(), e);
        }
    }
}
