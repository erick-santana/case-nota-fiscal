package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;

public class AliquotaPessoaFisicaPolicy implements AliquotaPolicy {

    @Override
    public boolean aplicavelPara(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime) {
        return tipoPessoa == TipoPessoa.FISICA;
    }

    @Override
    public double percentualPara(double valorTotalItens) {
        if (valorTotalItens < 500) return 0;
        if (valorTotalItens <= 2000) return 0.12;
        if (valorTotalItens <= 3500) return 0.15;
        return 0.17;
    }
}
