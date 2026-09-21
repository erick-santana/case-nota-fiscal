package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.Documento;
import br.com.itau.geradornotafiscal.model.Endereco;
import br.com.itau.geradornotafiscal.model.Finalidade;
import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.Pedido;
import br.com.itau.geradornotafiscal.model.Regiao;
import br.com.itau.geradornotafiscal.model.TipoDocumento;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import br.com.itau.geradornotafiscal.testsupport.OutboxTestConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-4.4 a REQ-4.10 de SPEC-04: validação declarativa do payload, sob a cadeia permissiva de
 * "test" (sem token — a cadeia JWT em si é coberta por {@link EndpointSegurancaTest} e
 * {@link PerfilNaoPrevistoFailClosedTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OutboxTestConfig.class)
class PedidoValidacaoTest {

    private static final String ENDPOINT = "/api/pedido/gerarNotaFiscal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void destinatarioSemEnderecosRetorna400NaoMais500() throws Exception {
        Pedido pedido = umPedidoBase();
        pedido.getDestinatario().setEnderecos(null);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void maisDeQuinhentosItensRetorna400() throws Exception {
        Pedido pedido = umPedidoBase();
        List<Item> muitosItens = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            muitosItens.add(new Item(String.valueOf(i), "Item " + i, 10.0, 1));
        }
        pedido.setItens(muitosItens);
        pedido.setValorTotalItens(5010.0);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void valorTotalItensDivergenteDaSomaRetorna400() throws Exception {
        Pedido pedido = umPedidoBase();
        pedido.setValorTotalItens(pedido.getValorTotalItens() + 100.0);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void valorTotalItensDentroDaToleranciaDeArredondamentoRetorna200() throws Exception {
        Pedido pedido = umPedidoBase();
        pedido.setValorTotalItens(pedido.getValorTotalItens() + 0.005);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isOk());
    }

    @Test
    void juridicaSemRegimeTributacaoRetorna400() throws Exception {
        Pedido pedido = umPedidoBase();
        pedido.getDestinatario().setTipoPessoa(TipoPessoa.JURIDICA);
        pedido.getDestinatario().setRegimeTributacao(null);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jsonSintaticamenteInvalidoRetorna400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ isso não é um json válido"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void respostaDeErroNaoExpoeStackTraceNemValorRecebido() throws Exception {
        Pedido pedido = umPedidoBase();
        pedido.getItens().get(0).setValorUnitario(-10.0);

        String corpo = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).doesNotContain("-10.0", "Exception", "\tat ");
        JsonNode raiz = objectMapper.readTree(corpo);
        assertThat(raiz.path("mensagem").asString()).isNotBlank();
        assertThat(raiz.path("campos")).isNotEmpty();
    }

    @Test
    void payloadDeExemploPessoaFisicaRetorna200() throws Exception {
        postarPayloadDoClasspath("paylods/teste-pf.json");
    }

    @Test
    void payloadDeExemploPessoaJuridicaRetorna200() throws Exception {
        postarPayloadDoClasspath("paylods/teste-pj-simples.json");
    }

    private void postarPayloadDoClasspath(String classpathLocation) throws Exception {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inputStream.readAllBytes()))
                    .andExpect(status().isOk());
        }
    }

    private Pedido umPedidoBase() {
        Destinatario destinatario = Destinatario.builder()
                .nome("Fixture de Teste")
                .tipoPessoa(TipoPessoa.FISICA)
                .documentos(List.of(new Documento("12345678900", TipoDocumento.CPF)))
                .enderecos(List.of(Endereco.builder()
                        .cep("00000000")
                        .logradouro("Rua Fixture")
                        .numero("1")
                        .estado("SP")
                        .finalidade(Finalidade.ENTREGA)
                        .regiao(Regiao.SUDESTE)
                        .build()))
                .build();

        return Pedido.builder()
                .idPedido(1)
                .data(LocalDate.of(2026, 1, 1))
                .valorTotalItens(100.0)
                .valorFrete(10.0)
                .itens(List.of(new Item("1", "Item fixture", 100.0, 1)))
                .destinatario(destinatario)
                .build();
    }
}
