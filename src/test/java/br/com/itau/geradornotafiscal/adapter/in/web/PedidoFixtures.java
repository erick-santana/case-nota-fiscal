package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.Documento;
import br.com.itau.geradornotafiscal.model.Endereco;
import br.com.itau.geradornotafiscal.model.Finalidade;
import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.Pedido;
import br.com.itau.geradornotafiscal.model.Regiao;
import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoDocumento;
import br.com.itau.geradornotafiscal.model.TipoPessoa;

import java.time.LocalDate;
import java.util.List;

final class PedidoFixtures {

    private PedidoFixtures() {
    }

    static Pedido umPedido(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime, double valorUnitarioDoItem, Regiao regiao) {
        return umPedidoComItens(tipoPessoa, regime, valorUnitarioDoItem, regiao, List.of(umItem("1", valorUnitarioDoItem, 1)));
    }

    static Pedido umPedidoComItens(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime, double valorTotalItens,
                                    Regiao regiao, List<Item> itens) {
        Destinatario.DestinatarioBuilder destinatario = Destinatario.builder()
                .nome("Fixture de Teste")
                .tipoPessoa(tipoPessoa)
                .regimeTributacao(regime)
                .documentos(List.of(umDocumento(tipoPessoa)))
                .enderecos(regiao == null ? List.of() : List.of(umEndereco(Finalidade.ENTREGA, regiao)));

        return Pedido.builder()
                .idPedido(1)
                .data(LocalDate.of(2026, 1, 1))
                .valorTotalItens(valorTotalItens)
                .valorFrete(100.0)
                .itens(itens)
                .destinatario(destinatario.build())
                .build();
    }

    static Item umItem(String idItem, double valorUnitario, int quantidade) {
        return new Item(idItem, "Item fixture " + idItem, valorUnitario, quantidade);
    }

    private static Documento umDocumento(TipoPessoa tipoPessoa) {
        return new Documento(
                tipoPessoa == TipoPessoa.FISICA ? "12345678900" : "12345678000199",
                tipoPessoa == TipoPessoa.FISICA ? TipoDocumento.CPF : TipoDocumento.CNPJ);
    }

    private static Endereco umEndereco(Finalidade finalidade, Regiao regiao) {
        return Endereco.builder()
                .cep("00000000")
                .logradouro("Rua Fixture")
                .numero("1")
                .estado("SP")
                .complemento("apto 1")
                .finalidade(finalidade)
                .regiao(regiao)
                .build();
    }
}
