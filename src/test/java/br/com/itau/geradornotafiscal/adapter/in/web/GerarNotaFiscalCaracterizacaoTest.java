package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.Pedido;
import br.com.itau.geradornotafiscal.model.Regiao;
import br.com.itau.geradornotafiscal.model.RegimeTributacaoPJ;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import br.com.itau.geradornotafiscal.testsupport.OutboxTestConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suíte de caracterização ancorada no contrato HTTP (SPEC-02). Não referencia nenhuma classe de
 * serviço/domínio de produção de propósito: esse é o seam que sobrevive à refatoração de SPEC-03
 * sem edição desta suíte (P5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OutboxTestConfig.class)
class GerarNotaFiscalCaracterizacaoTest {

    private static final String ENDPOINT = "/api/pedido/gerarNotaFiscal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest(name = "{0}/{1} valorTotalItens={2} -> aliquota={3}")
    @MethodSource("combinacoesAliquota")
    void deveAplicarAliquotaPorTipoPessoaRegimeEFaixa(TipoPessoa tipoPessoa, RegimeTributacaoPJ regime,
                                                       double valorTotalItens, double aliquotaEsperada) throws Exception {
        Pedido pedido = PedidoFixtures.umPedido(tipoPessoa, regime, valorTotalItens, Regiao.SUDESTE);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].valor_tributo_item")
                        .value(closeTo(valorTotalItens * aliquotaEsperada, 0.0001)));
    }

    static Stream<Arguments> combinacoesAliquota() {
        return Stream.of(
                // FISICA: < 500 / <= 2000 / <= 3500 / > 3500 -> 0 / 0,12 / 0,15 / 0,17
                Arguments.of(TipoPessoa.FISICA, null, 400.0, 0.0),
                Arguments.of(TipoPessoa.FISICA, null, 500.0, 0.12),
                Arguments.of(TipoPessoa.FISICA, null, 1500.0, 0.12),
                Arguments.of(TipoPessoa.FISICA, null, 2000.0, 0.12),
                Arguments.of(TipoPessoa.FISICA, null, 3000.0, 0.15),
                Arguments.of(TipoPessoa.FISICA, null, 3500.0, 0.15),
                Arguments.of(TipoPessoa.FISICA, null, 4000.0, 0.17),
                // JURIDICA/SIMPLES_NACIONAL: < 1000 / <= 2000 / <= 5000 / > 5000 -> 0,03 / 0,07 / 0,13 / 0,19
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 500.0, 0.03),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 1000.0, 0.07),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 1500.0, 0.07),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 2000.0, 0.07),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 3000.0, 0.13),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 5000.0, 0.13),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL, 6000.0, 0.19),
                // JURIDICA/LUCRO_REAL: < 1000 / <= 2000 / <= 5000 / > 5000 -> 0,03 / 0,09 / 0,15 / 0,20
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 500.0, 0.03),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 1000.0, 0.09),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 1500.0, 0.09),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 2000.0, 0.09),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 3000.0, 0.15),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 5000.0, 0.15),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_REAL, 6000.0, 0.20),
                // JURIDICA/LUCRO_PRESUMIDO: < 1000 / <= 2000 / <= 5000 / > 5000 -> 0,03 / 0,09 / 0,16 / 0,20
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 500.0, 0.03),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 1000.0, 0.09),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 1500.0, 0.09),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 2000.0, 0.09),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 3000.0, 0.16),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 5000.0, 0.16),
                Arguments.of(TipoPessoa.JURIDICA, RegimeTributacaoPJ.LUCRO_PRESUMIDO, 6000.0, 0.20)
        );
    }

    @ParameterizedTest(name = "regiao={0} -> multiplicador={1}")
    @MethodSource("combinacoesFrete")
    void deveAplicarMultiplicadorDeFretePorRegiao(Regiao regiao, double multiplicadorEsperado) throws Exception {
        Pedido pedido = PedidoFixtures.umPedido(TipoPessoa.FISICA, null, 100.0, regiao);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valor_frete")
                        .value(closeTo(pedido.getValorFrete() * multiplicadorEsperado, 0.0001)));
    }

    static Stream<Arguments> combinacoesFrete() {
        return Stream.of(
                Arguments.of(Regiao.NORTE, 1.08),
                Arguments.of(Regiao.NORDESTE, 1.085),
                Arguments.of(Regiao.CENTRO_OESTE, 1.07),
                Arguments.of(Regiao.SUDESTE, 1.048),
                Arguments.of(Regiao.SUL, 1.06),
                Arguments.of(null, 0.0)
        );
    }

    @Test
    void regimeOutrosProduzErroExplicito() throws Exception {
        // D-03: RegimeTributacaoPJ.OUTROS (e qualquer combinação sem regra definida) passa a responder
        // 422, em vez de emitir uma nota com itens: [] em silêncio. Único caso desta suíte alterado por
        // SPEC-03, no mesmo commit que implementa a mudança (CONSTITUICAO.md D-03).
        Pedido pedido = PedidoFixtures.umPedido(TipoPessoa.JURIDICA, RegimeTributacaoPJ.OUTROS, 1000.0, Regiao.SUDESTE);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void tributoPorItemIgnoraQuantidade_comportamentoAtualTravado() throws Exception {
        // Divergência conhecida (D-05): valorTributoItem = valorUnitario * aliquota, sem multiplicar pela quantidade.
        // Travada de propósito: mudar a fórmula é decisão de negócio (impacto fiscal), não refatoração. Ver CONSTITUICAO.md D-05.
        double valorUnitario = 730.0;
        int quantidade = 8;
        double valorTotalItens = valorUnitario * quantidade; // 5840.0, mesma proporção do payload de exemplo PJ
        Item item = PedidoFixtures.umItem("1", valorUnitario, quantidade);
        Pedido pedido = PedidoFixtures.umPedidoComItens(TipoPessoa.JURIDICA, RegimeTributacaoPJ.SIMPLES_NACIONAL,
                valorTotalItens, Regiao.SUDESTE, List.of(item));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].valor_tributo_item").value(closeTo(valorUnitario * 0.19, 0.0001)));
    }

    @Test
    void pedidoComCincoItensNaoDisparaAtrasoAnomalo() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedidoComNItens(5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(5));
    }

    @Test
    void pedidoComSeisItensDisparaAtrasoAnomaloMasAindaRespondeComSucesso() throws Exception {
        // Limiar real do bug de performance é > 5 itens (EntregaIntegrationPort.java:10), ou seja, a partir de 6.
        // Sem asserção de tempo: isso é responsabilidade dos cenários k6 de SPEC-03 (REQ-2.5), aqui só o comportamento.
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedidoComNItens(6))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(6));
    }

    @Test
    void mesmoPayloadDuasVezesProduzMesmoResultado() throws Exception {
        Pedido pedido = PedidoFixtures.umPedido(TipoPessoa.FISICA, null, 1500.0, Regiao.SUDESTE);
        String payload = objectMapper.writeValueAsString(pedido);

        JsonNode resposta1 = executar(payload);
        JsonNode resposta2 = executar(payload);

        assertThat(resposta1.path("itens").size()).isEqualTo(1);
        assertThat(resposta2.path("itens").size()).isEqualTo(1);
        assertThat(resposta1.path("itens").get(0).path("valor_tributo_item").asDouble())
                .isEqualTo(resposta2.path("itens").get(0).path("valor_tributo_item").asDouble());
        assertThat(resposta1.path("valor_total_itens").asDouble())
                .isEqualTo(resposta2.path("valor_total_itens").asDouble());
        assertThat(resposta1.path("valor_frete").asDouble())
                .isEqualTo(resposta2.path("valor_frete").asDouble());
    }

    @Test
    void pedidoPequenoAposPedidoGrandeNaoTrazItensDoAnterior() throws Exception {
        executar(objectMapper.writeValueAsString(pedidoComNItens(7)));

        Pedido pedidoPequeno = PedidoFixtures.umPedido(TipoPessoa.FISICA, null, 1500.0, Regiao.SUDESTE);
        JsonNode resposta = executar(objectMapper.writeValueAsString(pedidoPequeno));

        assertThat(resposta.path("itens").size()).isEqualTo(1);
        assertThat(resposta.path("itens").get(0).path("valor_tributo_item").asDouble())
                .isEqualTo(1500.0 * 0.12);
    }

    @Test
    void pedidosConcorrentesNaoSeContaminam() throws Exception {
        double[] valoresUnitarios = {111.0, 222.0, 333.0, 444.0, 555.0};
        CountDownLatch partida = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(valoresUnitarios.length);
        List<Future<JsonNode>> respostas = new ArrayList<>();

        try {
            for (double valorUnitario : valoresUnitarios) {
                respostas.add(executor.submit(() -> {
                    partida.await();
                    Pedido pedido = PedidoFixtures.umPedido(TipoPessoa.FISICA, null, valorUnitario, Regiao.SUDESTE);
                    return executar(objectMapper.writeValueAsString(pedido));
                }));
            }
            partida.countDown();

            for (int i = 0; i < valoresUnitarios.length; i++) {
                JsonNode resposta = respostas.get(i).get(30, TimeUnit.SECONDS);
                assertThat(resposta.path("itens").size()).isEqualTo(1);
                assertThat(resposta.path("itens").get(0).path("valor_unitario").asDouble())
                        .isEqualTo(valoresUnitarios[i]);
            }
        } finally {
            executor.shutdown();
        }
    }

    private JsonNode executar(String payload) throws Exception {
        String resposta = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resposta);
    }

    private Pedido pedidoComNItens(int quantidadeDeItens) {
        List<Item> itens = new ArrayList<>();
        for (int i = 1; i <= quantidadeDeItens; i++) {
            itens.add(PedidoFixtures.umItem(String.valueOf(i), 100.0, 1));
        }
        return PedidoFixtures.umPedidoComItens(TipoPessoa.FISICA, null, 100.0 * quantidadeDeItens, Regiao.SUDESTE, itens);
    }
}
