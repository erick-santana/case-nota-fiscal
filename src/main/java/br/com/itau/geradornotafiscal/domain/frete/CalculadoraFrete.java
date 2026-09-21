package br.com.itau.geradornotafiscal.domain.frete;

import br.com.itau.geradornotafiscal.model.Endereco;
import br.com.itau.geradornotafiscal.model.Finalidade;
import br.com.itau.geradornotafiscal.model.Regiao;

import java.util.List;

public class CalculadoraFrete {

    public double calcular(List<Endereco> enderecos, double valorFrete) {
        Regiao regiao = enderecos.stream()
                .filter(endereco -> endereco.getFinalidade() == Finalidade.ENTREGA
                        || endereco.getFinalidade() == Finalidade.COBRANCA_ENTREGA)
                .map(Endereco::getRegiao)
                .findFirst()
                .orElse(null);

        if (regiao == null) {
            return 0;
        }

        return switch (regiao) {
            case NORTE -> valorFrete * 1.08;
            case NORDESTE -> valorFrete * 1.085;
            case CENTRO_OESTE -> valorFrete * 1.07;
            case SUDESTE -> valorFrete * 1.048;
            case SUL -> valorFrete * 1.06;
        };
    }
}
