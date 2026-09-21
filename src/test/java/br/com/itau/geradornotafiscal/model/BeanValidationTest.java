package br.com.itau.geradornotafiscal.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitários de {@link Validator} por campo da tabela de desenho de SPEC-04 (verificação #5):
 * ausente/nulo/vazio/negativo produz a violação esperada. Sem Spring, plain Bean Validation.
 */
class BeanValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void pedidoValidoNaoProduzViolacao() {
        assertThat(validator.validate(umPedidoValido())).isEmpty();
    }

    @Test
    void idPedidoNuloOuNaoPositivoProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setIdPedido(null);
        assertThat(violacoesDoCampo(pedido, "idPedido")).isNotEmpty();

        pedido.setIdPedido(0);
        assertThat(violacoesDoCampo(pedido, "idPedido")).isNotEmpty();

        pedido.setIdPedido(-1);
        assertThat(violacoesDoCampo(pedido, "idPedido")).isNotEmpty();
    }

    @Test
    void dataNulaProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setData(null);
        assertThat(violacoesDoCampo(pedido, "data")).isNotEmpty();
    }

    @Test
    void valorTotalItensNuloZeroOuNegativoProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setValorTotalItens(null);
        assertThat(violacoesDoCampo(pedido, "valorTotalItens")).isNotEmpty();

        pedido = umPedidoValido();
        pedido.setValorTotalItens(0.0);
        assertThat(violacoesDoCampo(pedido, "valorTotalItens")).isNotEmpty();

        pedido = umPedidoValido();
        pedido.setValorTotalItens(-1.0);
        assertThat(violacoesDoCampo(pedido, "valorTotalItens")).isNotEmpty();
    }

    @Test
    void valorFreteNuloOuNegativoProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setValorFrete(null);
        assertThat(violacoesDoCampo(pedido, "valorFrete")).isNotEmpty();

        pedido = umPedidoValido();
        pedido.setValorFrete(-1.0);
        assertThat(violacoesDoCampo(pedido, "valorFrete")).isNotEmpty();
    }

    @Test
    void itensVazioOuAcimaDoLimiteProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setItens(List.of());
        assertThat(violacoesDoCampo(pedido, "itens")).isNotEmpty();

        pedido = umPedidoValido();
        pedido.setItens(List.of());
        List<Item> muitos = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            muitos.add(umItemValido());
        }
        pedido.setItens(muitos);
        assertThat(violacoesDoCampo(pedido, "itens")).isNotEmpty();
    }

    @Test
    void destinatarioNuloProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setDestinatario(null);
        assertThat(violacoesDoCampo(pedido, "destinatario")).isNotEmpty();
    }

    @Test
    void valorTotalItensDivergenteDaSomaProduzViolacao() {
        Pedido pedido = umPedidoValido();
        pedido.setValorTotalItens(pedido.getValorTotalItens() + 50.0);
        assertThat(validator.validate(pedido)).isNotEmpty();
    }

    @Test
    void itemComCamposAusentesProduzViolacao() {
        Item item = umItemValido();
        item.setIdItem(" ");
        assertThat(violacoesDoCampo(item, "idItem")).isNotEmpty();

        item = umItemValido();
        item.setDescricao(null);
        assertThat(violacoesDoCampo(item, "descricao")).isNotEmpty();

        item = umItemValido();
        item.setValorUnitario(null);
        assertThat(violacoesDoCampo(item, "valorUnitario")).isNotEmpty();

        item = umItemValido();
        item.setValorUnitario(-1.0);
        assertThat(violacoesDoCampo(item, "valorUnitario")).isNotEmpty();

        item = umItemValido();
        item.setQuantidade(null);
        assertThat(violacoesDoCampo(item, "quantidade")).isNotEmpty();

        item = umItemValido();
        item.setQuantidade(0);
        assertThat(violacoesDoCampo(item, "quantidade")).isNotEmpty();
    }

    @Test
    void itemComValorUnitarioZeroENaoRejeitado() {
        Item item = umItemValido();
        item.setValorUnitario(0.0);
        assertThat(violacoesDoCampo(item, "valorUnitario")).isEmpty();
    }

    @Test
    void destinatarioComCamposAusentesProduzViolacao() {
        Destinatario destinatario = umDestinatarioValido(TipoPessoa.FISICA, null);
        destinatario.setNome(" ");
        assertThat(violacoesDoCampo(destinatario, "nome")).isNotEmpty();

        destinatario = umDestinatarioValido(TipoPessoa.FISICA, null);
        destinatario.setTipoPessoa(null);
        assertThat(violacoesDoCampo(destinatario, "tipoPessoa")).isNotEmpty();

        destinatario = umDestinatarioValido(TipoPessoa.FISICA, null);
        destinatario.setDocumentos(null);
        assertThat(violacoesDoCampo(destinatario, "documentos")).isNotEmpty();

        destinatario = umDestinatarioValido(TipoPessoa.FISICA, null);
        destinatario.setEnderecos(null);
        assertThat(violacoesDoCampo(destinatario, "enderecos")).isNotEmpty();
    }

    @Test
    void destinatarioComEnderecosVazioNaoEhRejeitado() {
        Destinatario destinatario = umDestinatarioValido(TipoPessoa.FISICA, null);
        destinatario.setEnderecos(List.of());
        assertThat(violacoesDoCampo(destinatario, "enderecos")).isEmpty();
    }

    @Test
    void juridicaSemRegimeTributacaoProduzViolacao() {
        Destinatario destinatario = umDestinatarioValido(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL);
        destinatario.setRegimeTributacao(null);
        assertThat(validator.validate(destinatario)).isNotEmpty();
    }

    @Test
    void fisicaSemRegimeTributacaoNaoEhRejeitada() {
        Destinatario destinatario = umDestinatarioValido(TipoPessoa.FISICA, null);
        assertThat(validator.validate(destinatario)).isEmpty();
    }

    @Test
    void documentoComCamposAusentesProduzViolacao() {
        Documento documento = new Documento("123", TipoDocumento.CPF);
        documento.setNumero(" ");
        assertThat(violacoesDoCampo(documento, "numero")).isNotEmpty();

        documento = new Documento("123", TipoDocumento.CPF);
        documento.setTipo(null);
        assertThat(violacoesDoCampo(documento, "tipo")).isNotEmpty();
    }

    @Test
    void enderecoComCamposAusentesProduzViolacao() {
        Endereco endereco = umEnderecoValido();
        endereco.setCep(" ");
        assertThat(violacoesDoCampo(endereco, "cep")).isNotEmpty();

        endereco = umEnderecoValido();
        endereco.setLogradouro(null);
        assertThat(violacoesDoCampo(endereco, "logradouro")).isNotEmpty();

        endereco = umEnderecoValido();
        endereco.setNumero(" ");
        assertThat(violacoesDoCampo(endereco, "numero")).isNotEmpty();

        endereco = umEnderecoValido();
        endereco.setEstado(null);
        assertThat(violacoesDoCampo(endereco, "estado")).isNotEmpty();

        endereco = umEnderecoValido();
        endereco.setFinalidade(null);
        assertThat(violacoesDoCampo(endereco, "finalidade")).isNotEmpty();

        endereco = umEnderecoValido();
        endereco.setRegiao(null);
        assertThat(violacoesDoCampo(endereco, "regiao")).isNotEmpty();
    }

    @Test
    void enderecoSemComplementoNaoEhRejeitado() {
        Endereco endereco = umEnderecoValido();
        endereco.setComplemento(null);
        assertThat(validator.validate(endereco)).isEmpty();
    }

    private <T> Set<ConstraintViolation<T>> violacoesDoCampo(T objeto, String campo) {
        return validator.validate(objeto).stream()
                .filter(v -> v.getPropertyPath().toString().equals(campo))
                .collect(java.util.stream.Collectors.toSet());
    }

    private Pedido umPedidoValido() {
        return Pedido.builder()
                .idPedido(1)
                .data(LocalDate.of(2026, 1, 1))
                .valorTotalItens(100.0)
                .valorFrete(10.0)
                .itens(List.of(umItemValido()))
                .destinatario(umDestinatarioValido(TipoPessoa.FISICA, null))
                .build();
    }

    private Item umItemValido() {
        return new Item("1", "Item de teste", 100.0, 1);
    }

    private Destinatario umDestinatarioValido(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime) {
        return Destinatario.builder()
                .nome("Fixture de Teste")
                .tipoPessoa(tipoPessoa)
                .regimeTributacao(regime)
                .documentos(List.of(new Documento("12345678900", TipoDocumento.CPF)))
                .enderecos(List.of(umEnderecoValido()))
                .build();
    }

    private Endereco umEnderecoValido() {
        return Endereco.builder()
                .cep("00000000")
                .logradouro("Rua Fixture")
                .numero("1")
                .estado("SP")
                .complemento("apto 1")
                .finalidade(Finalidade.ENTREGA)
                .regiao(Regiao.SUDESTE)
                .build();
    }
}
