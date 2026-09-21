package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;

public class AliquotaLucroRealPolicy implements AliquotaPolicy {

    @Override
    public boolean aplicavelPara(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime) {
        return tipoPessoa == TipoPessoa.JURIDICA && regime == RegimeTributacaoPJ.LUCRO_REAL;
    }

    @Override
    public double percentualPara(double valorTotalItens) {
        if (valorTotalItens < 1000) return 0.03;
        if (valorTotalItens <= 2000) return 0.09;
        if (valorTotalItens <= 5000) return 0.15;
        return 0.20;
    }
}
