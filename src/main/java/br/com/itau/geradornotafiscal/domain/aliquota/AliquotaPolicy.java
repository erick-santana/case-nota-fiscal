package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;

public interface AliquotaPolicy {

    boolean aplicavelPara(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime);

    double percentualPara(double valorTotalItens);
}
