package br.com.itau.geradornotafiscal.adapter.out.entrega;

import br.com.itau.geradornotafiscal.application.port.out.EntregaIntegrationPort;
import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import org.springframework.stereotype.Component;

@Component
public class EntregaIntegrationAdapter implements EntregaIntegrationPort {

    @Override
    public void criarAgendamento(NotaFiscal notaFiscal) {
        try {
            // Latência fixa da integração externa de agendamento — preservada de propósito.
            // Não depende da quantidade de itens: a penalidade condicional que existia aqui
            // era desproporcional e artificial, sem correspondência com uma chamada real.
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegracaoDownstreamException("entrega", notaFiscal.getIdNotaFiscal(), e);
        }
    }
}
