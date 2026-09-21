package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AliquotaLucroRealPolicyTest {

    private final AliquotaLucroRealPolicy policy = new AliquotaLucroRealPolicy();

    @ParameterizedTest
    @CsvSource({
            "500, 0.03",
            "999.99, 0.03",
            "1000, 0.09",
            "2000, 0.09",
            "2000.01, 0.15",
            "5000, 0.15",
            "5000.01, 0.20",
            "6000, 0.20",
    })
    void aplicaFaixaCorretaPorValorTotalItens(double valorTotalItens, double percentualEsperado) {
        assertThat(policy.percentualPara(valorTotalItens)).isEqualTo(percentualEsperado);
    }

    @Test
    void aplicavelApenasParaJuridicaLucroReal() {
        assertThat(policy.aplicavelPara(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL)).isTrue();
        assertThat(policy.aplicavelPara(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL)).isFalse();
        assertThat(policy.aplicavelPara(TipoPessoa.FISICA, null)).isFalse();
    }
}
