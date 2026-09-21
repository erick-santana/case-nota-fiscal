package br.com.itau.geradornotafiscal.application.port.out;

import br.com.itau.geradornotafiscal.model.NotaFiscal;

public interface EstoqueNotificacaoPort {

    void notificar(NotaFiscal notaFiscal);
}
