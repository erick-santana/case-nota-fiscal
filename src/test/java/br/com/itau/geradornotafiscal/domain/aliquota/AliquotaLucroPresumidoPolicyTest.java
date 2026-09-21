package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AliquotaLucroPresumidoPolicyTest {

    private final AliquotaLucroPresumidoPolicy policy = new AliquotaLucroPresumidoPolicy();

    @ParameterizedTest
    @CsvSource({
            "500, 0.03",
            "999.99, 0.03",
            "1000, 0.09",
            "2000, 0.09",
            "2000.01, 0.16",
            "5000, 0.16",
            "5000.01, 0.20",
            "6000, 0.20",
    })
    void aplicaFaixaCorretaPorValorTotalItens(double valorTotalItens, double percentualEsperado) {
        assertThat(policy.percentualPara(valorTotalItens)).isEqualTo(percentualEsperado);
    }

    @Test
    void aplicavelApenasParaJuridicaLucroPresumido() {
        assertThat(policy.aplicavelPara(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO)).isTrue();
        assertThat(policy.aplicavelPara(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL)).isFalse();
        assertThat(policy.aplicavelPara(TipoPessoa.FISICA, null)).isFalse();
    }
}
