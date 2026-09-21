package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;
import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculadoraAliquotaTest {

    private final CalculadoraAliquota calculadoraAliquota = new CalculadoraAliquota(
            List.of(new AliquotaPessoaFisicaPolicy(), new AliquotaSimplesNacionalPolicy(),
                    new AliquotaLucroRealPolicy(), new AliquotaLucroPresumidoPolicy()),
            new CalculadoraTributoItem());

    @Test
    void resolvePolicyCorretaPorTipoPessoaERegime() {
        Destinatario destinatario = Destinatario.builder()
                .tipoPessoa(TipoPessoa.JURIDICA)
                .regimeTributacao(RegimeTributacaoPJ.LUCRO_REAL)
                .build();
        Item item = new Item("1", "Item", 1000.0, 1);

        List<ItemNotaFiscal> resultado = calculadoraAliquota.calcular(destinatario, 1000.0, List.of(item));

        assertThat(resultado.get(0).getValorTributoItem()).isEqualTo(1000.0 * 0.09);
    }

    @Test
    void regimeSemRegraDefinidaLancaExcecao() {
        Destinatario destinatario = Destinatario.builder()
                .tipoPessoa(TipoPessoa.JURIDICA)
                .regimeTributacao(RegimeTributacaoPJ.OUTROS)
                .build();

        assertThatThrownBy(() -> calculadoraAliquota.calcular(destinatario, 1000.0, List.of()))
                .isInstanceOf(RegimeTributacaoNaoSuportadoException.class);
    }
}
