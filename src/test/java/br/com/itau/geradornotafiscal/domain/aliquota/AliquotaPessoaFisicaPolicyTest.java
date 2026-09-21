package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AliquotaPessoaFisicaPolicyTest {

    private final AliquotaPessoaFisicaPolicy policy = new AliquotaPessoaFisicaPolicy();

    @ParameterizedTest
    @CsvSource({
            "400, 0.0",
            "499.99, 0.0",
            "500, 0.12",
            "2000, 0.12",
            "2000.01, 0.15",
            "3500, 0.15",
            "3500.01, 0.17",
            "4000, 0.17",
    })
    void aplicaFaixaCorretaPorValorTotalItens(double valorTotalItens, double percentualEsperado) {
        assertThat(policy.percentualPara(valorTotalItens)).isEqualTo(percentualEsperado);
    }

    @Test
    void aplicavelApenasParaPessoaFisica() {
        assertThat(policy.aplicavelPara(TipoPessoa.FISICA, null)).isTrue();
        assertThat(policy.aplicavelPara(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL)).isFalse();
    }
}
