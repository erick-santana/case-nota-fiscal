package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalculadoraTributoItemTest {

    private final CalculadoraTributoItem calculadora = new CalculadoraTributoItem();

    @Test
    void calculaValorTributoPorItemIgnorandoQuantidade() {
        Item item = new Item("1", "Item", 100.0, 5);

        List<ItemNotaFiscal> resultado = calculadora.calcular(List.of(item), 0.12);

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).getValorTributoItem()).isEqualTo(12.0);
        assertThat(resultado.get(0).getQuantidade()).isEqualTo(5);
    }

    @Test
    void duasChamadasSucessivasSobreListasDiferentesSaoIndependentes() {
        List<ItemNotaFiscal> primeira = calculadora.calcular(
                List.of(new Item("1", "A", 10.0, 1), new Item("2", "B", 20.0, 1),
                        new Item("3", "C", 30.0, 1), new Item("4", "D", 40.0, 1),
                        new Item("5", "E", 50.0, 1), new Item("6", "F", 60.0, 1)),
                0.10);
        List<ItemNotaFiscal> segunda = calculadora.calcular(List.of(new Item("7", "G", 100.0, 1)), 0.10);

        assertThat(primeira).hasSize(6);
        assertThat(segunda).hasSize(1);
        assertThat(segunda.get(0).getIdItem()).isEqualTo("7");
    }
}
