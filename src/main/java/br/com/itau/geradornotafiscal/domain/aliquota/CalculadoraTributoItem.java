package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;

import java.util.ArrayList;
import java.util.List;

public class CalculadoraTributoItem {

    public List<ItemNotaFiscal> calcular(List<Item> itens, double percentual) {
        List<ItemNotaFiscal> resultado = new ArrayList<>();
        for (Item item : itens) {
            resultado.add(ItemNotaFiscal.builder()
                    .idItem(item.getIdItem())
                    .descricao(item.getDescricao())
                    .valorUnitario(item.getValorUnitario())
                    .quantidade(item.getQuantidade())
                    .valorTributoItem(item.getValorUnitario() * percentual) // D-05: ignora quantidade, preservado
                    .build());
        }
        return resultado;
    }
}
