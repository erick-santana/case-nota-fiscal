package br.com.itau.geradornotafiscal.domain.frete;

import br.com.itau.geradornotafiscal.model.Endereco;
import br.com.itau.geradornotafiscal.model.Finalidade;
import br.com.itau.geradornotafiscal.model.Regiao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CalculadoraFreteTest {

    private final CalculadoraFrete calculadoraFrete = new CalculadoraFrete();

    @ParameterizedTest
    @EnumSource(Regiao.class)
    void aplicaMultiplicadorPorRegiao(Regiao regiao) {
        double multiplicador = switch (regiao) {
            case NORTE -> 1.08;
            case NORDESTE -> 1.085;
            case CENTRO_OESTE -> 1.07;
            case SUDESTE -> 1.048;
            case SUL -> 1.06;
        };
        Endereco endereco = Endereco.builder().finalidade(Finalidade.ENTREGA).regiao(regiao).build();

        double resultado = calculadoraFrete.calcular(List.of(endereco), 100.0);

        assertThat(resultado).isCloseTo(100.0 * multiplicador, within(0.0001));
    }

    @Test
    void aceitaFinalidadeCobrancaEntregaAlemDeEntrega() {
        Endereco endereco = Endereco.builder().finalidade(Finalidade.COBRANCA_ENTREGA).regiao(Regiao.SUL).build();

        assertThat(calculadoraFrete.calcular(List.of(endereco), 100.0)).isCloseTo(106.0, within(0.0001));
    }

    @Test
    void semEnderecoDeEntregaOuCobrancaEntregaRetornaZero() {
        Endereco enderecoCobranca = Endereco.builder().finalidade(Finalidade.COBRANCA).regiao(Regiao.SUL).build();

        assertThat(calculadoraFrete.calcular(List.of(enderecoCobranca), 100.0)).isZero();
        assertThat(calculadoraFrete.calcular(List.of(), 100.0)).isZero();
    }
}
