package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;

public class AliquotaSimplesNacionalPolicy implements AliquotaPolicy {

    @Override
    public boolean aplicavelPara(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime) {
        return tipoPessoa == TipoPessoa.JURIDICA && regime == RegimeTributacaoPJ.SIMPLES_NACIONAL;
    }

    @Override
    public double percentualPara(double valorTotalItens) {
        if (valorTotalItens < 1000) return 0.03;
        if (valorTotalItens <= 2000) return 0.07;
        if (valorTotalItens <= 5000) return 0.13;
        return 0.19;
    }
}
