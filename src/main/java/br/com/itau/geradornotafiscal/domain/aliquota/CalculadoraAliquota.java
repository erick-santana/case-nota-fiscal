package br.com.itau.geradornotafiscal.domain.aliquota;

import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;

import java.util.List;

public class CalculadoraAliquota {

    private final List<AliquotaPolicy> policies;
    private final CalculadoraTributoItem calculadoraTributoItem;

    public CalculadoraAliquota(List<AliquotaPolicy> policies, CalculadoraTributoItem calculadoraTributoItem) {
        this.policies = policies;
        this.calculadoraTributoItem = calculadoraTributoItem;
    }

    public List<ItemNotaFiscal> calcular(Destinatario destinatario, double valorTotalItens, List<Item> itens) {
        AliquotaPolicy policy = policies.stream()
                .filter(p -> p.aplicavelPara(destinatario.getTipoPessoa(), destinatario.getRegimeTributacao()))
                .findFirst()
                .orElseThrow(() -> new RegimeTributacaoNaoSuportadoException(
                        destinatario.getTipoPessoa(), destinatario.getRegimeTributacao()));

        return calculadoraTributoItem.calcular(itens, policy.percentualPara(valorTotalItens));
    }
}
