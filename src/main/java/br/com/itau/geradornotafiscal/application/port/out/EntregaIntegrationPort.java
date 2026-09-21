package br.com.itau.geradornotafiscal.application.port.out;

import br.com.itau.geradornotafiscal.model.NotaFiscal;

public interface EntregaIntegrationPort {

    void criarAgendamento(NotaFiscal notaFiscal);
}
