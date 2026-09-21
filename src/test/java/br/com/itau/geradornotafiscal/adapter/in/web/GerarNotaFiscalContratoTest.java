package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.model.Pedido;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato de entrada (desserialização dos payloads de exemplo) e de saída (conjunto exato de
 * campos nos quatro níveis de aninhamento) do endpoint HTTP — o seam estável de SPEC-02.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GerarNotaFiscalContratoTest {

    private static final String ENDPOINT = "/api/pedido/gerarNotaFiscal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void payloadDeExemploPessoaFisicaDesserializaSemPerdaDeCampoMapeado() throws Exception {
        Pedido pedido = lerPedidoDoPayload("paylods/teste-pf.json");

        assertThat(pedido.getIdPedido()).isEqualTo(1);
        assertThat(pedido.getData()).isEqualTo(LocalDate.of(2022, 5, 1));
        assertThat(pedido.getValorTotalItens()).isEqualTo(100.0);
        assertThat(pedido.getValorFrete()).isEqualTo(10.0);
        assertThat(pedido.getItens()).hasSize(1);
        assertThat(pedido.getItens().get(0).getDescricao()).isEqualTo("Teclado USB");
        assertThat(pedido.getItens().get(0).getValorUnitario()).isEqualTo(50.0);
        assertThat(pedido.getItens().get(0).getQuantidade()).isEqualTo(2);
        assertThat(pedido.getDestinatario().getNome()).isEqualTo("John Doe");
        assertThat(pedido.getDestinatario().getTipoPessoa().name()).isEqualTo("FISICA");
        assertThat(pedido.getDestinatario().getDocumentos()).hasSize(1);
        assertThat(pedido.getDestinatario().getDocumentos().get(0).getNumero()).isEqualTo("88740347095");
        assertThat(pedido.getDestinatario().getDocumentos().get(0).getTipo().name()).isEqualTo("CPF");
        assertThat(pedido.getDestinatario().getEnderecos()).hasSize(1);
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getCep()).isEqualTo("03105003");
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getLogradouro()).isEqualTo("Av do estado");
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getNumero()).isEqualTo("5533");
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getEstado()).isEqualTo("SP");
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getComplemento()).isEqualTo("4 anndar b");
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getFinalidade().name()).isEqualTo("ENTREGA");
        assertThat(pedido.getDestinatario().getEnderecos().get(0).getRegiao().name()).isEqualTo("SUDESTE");
    }

    @Test
    void payloadDeExemploPessoaJuridicaDesserializaSemPerdaDeCampoMapeado() throws Exception {
        Pedido pedido = lerPedidoDoPayload("paylods/teste-pj-simples.json");

        assertThat(pedido.getValorTotalItens()).isEqualTo(5840.0);
        assertThat(pedido.getValorFrete()).isEqualTo(72.0);
        assertThat(pedido.getItens()).hasSize(1);
        assertThat(pedido.getItens().get(0).getDescricao()).isEqualTo("Monitor LCD SAMSUNG");
        assertThat(pedido.getItens().get(0).getValorUnitario()).isEqualTo(730.0);
        assertThat(pedido.getItens().get(0).getQuantidade()).isEqualTo(8);
        assertThat(pedido.getDestinatario().getTipoPessoa().name()).isEqualTo("JURIDICA");
        assertThat(pedido.getDestinatario().getRegimeTributacao().name()).isEqualTo("SIMPLES_NACIONAL");
        assertThat(pedido.getDestinatario().getDocumentos().get(0).getNumero()).isEqualTo("49695613000180");
        assertThat(pedido.getDestinatario().getDocumentos().get(0).getTipo().name()).isEqualTo("CNPJ");
    }

    @Test
    void respostaContemApenasOsCamposDoContratoNosQuatroNiveis() throws Exception {
        Pedido pedido = lerPedidoDoPayload("paylods/teste-pj-simples.json");

        String corpoResposta = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode raiz = objectMapper.readTree(corpoResposta);

        assertThat(campos(raiz)).containsExactlyInAnyOrder(
                "id_nota_fiscal", "data", "valor_total_itens", "valor_frete", "itens", "destinatario");
        assertThat(campos(raiz.path("itens").get(0))).containsExactlyInAnyOrder(
                "id_item", "descricao", "valor_unitario", "quantidade", "valor_tributo_item");
        assertThat(campos(raiz.path("destinatario"))).containsExactlyInAnyOrder(
                "nome", "tipo_pessoa", "regime_tributacao", "documentos", "enderecos");
        // Trava, de passagem, o descarte de bairro/cidade/pais do payload de entrada (D-06): não devem reaparecer aqui.
        assertThat(campos(raiz.path("destinatario").path("enderecos").get(0))).containsExactlyInAnyOrder(
                "cep", "logradouro", "numero", "estado", "complemento", "finalidade", "regiao");
        assertThat(campos(raiz.path("destinatario").path("documentos").get(0))).containsExactlyInAnyOrder(
                "numero", "tipo");
    }

    private Pedido lerPedidoDoPayload(String classpathLocation) throws Exception {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readValue(inputStream, Pedido.class);
        }
    }

    private Set<String> campos(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }
}
