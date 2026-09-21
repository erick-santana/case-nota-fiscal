package br.com.itau.geradornotafiscal.application.port.in;

import br.com.itau.geradornotafiscal.model.NotaFiscal;
import br.com.itau.geradornotafiscal.model.Pedido;

public interface GerarNotaFiscalUseCase {

    NotaFiscal gerarNotaFiscal(Pedido pedido);
}
