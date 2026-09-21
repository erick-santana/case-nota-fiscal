package br.com.itau.geradornotafiscal.adapter.out.entrega;

import br.com.itau.geradornotafiscal.application.port.out.EntregaIntegrationPort;
import br.com.itau.geradornotafiscal.application.port.out.EntregaNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import org.springframework.stereotype.Component;

@Component
public class EntregaAdapter implements EntregaNotificacaoPort {

    private final EntregaIntegrationPort entregaIntegrationPort;

    public EntregaAdapter(EntregaIntegrationPort entregaIntegrationPort) {
        this.entregaIntegrationPort = entregaIntegrationPort;
    }

    @Override
    public void agendar(NotaFiscal notaFiscal) {
        try {
            // Simula o preparo interno do agendamento de entrega
            Thread.sleep(150);
            entregaIntegrationPort.criarAgendamento(notaFiscal);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegracaoDownstreamException("entrega", notaFiscal.getIdNotaFiscal(), e);
        }
    }
}
