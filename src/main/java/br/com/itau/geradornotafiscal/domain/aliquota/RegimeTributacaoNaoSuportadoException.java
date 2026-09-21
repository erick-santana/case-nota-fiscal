package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;

public class RegimeTributacaoNaoSuportadoException extends RuntimeException {

    public RegimeTributacaoNaoSuportadoException(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime) {
        super("Nenhuma regra de alíquota aplicável para tipoPessoa=%s, regimeTributacao=%s".formatted(tipoPessoa, regime));
    }
}
