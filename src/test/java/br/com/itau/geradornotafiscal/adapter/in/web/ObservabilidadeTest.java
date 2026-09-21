package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.model.Pedido;
import br.com.itau.geradornotafiscal.model.Regiao;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Ponta a ponta de SPEC-05, com portas HTTP reais (não MockMvc, que não sobe a porta separada de
 * management): isolamento de rede do Actuator (REQ-5.1), probes (REQ-5.2), métricas por porta de
 * saída (REQ-5.4) e log estruturado correlacionado sem dado pessoal (REQ-5.5). Usa um DynamoDB
 * Local real (Testcontainers) para o outbox de SPEC-07 — precisa que o {@code PutItem} do
 * adapter real de fato aconteça para provar a métrica por adaptador.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,prometheus,info",
                "management.endpoint.health.probes.enabled=true",
                "management.health.livenessstate.enabled=true",
                "management.health.readinessstate.enabled=true",
                "management.prometheus.metrics.export.enabled=true"
        })
class ObservabilidadeTest {

    @Container
    private static final GenericContainer<?> DYNAMODB_LOCAL = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        registry.add("app.outbox.dynamodb.endpoint-override",
                () -> "http://%s:%d".formatted(DYNAMODB_LOCAL.getHost(), DYNAMODB_LOCAL.getMappedPort(8000)));
        registry.add("app.outbox.dynamodb.auto-create-table", () -> true);
    }

    @LocalServerPort
    private int appPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        appLogger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        appLogger().detachAppender(appender);
    }

    private Logger appLogger() {
        return (Logger) LoggerFactory.getLogger("br.com.itau.geradornotafiscal");
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void actuatorNaoEAlcancavelPelaPortaDaAplicacaoESoRespondeNaPortaDeManagement() throws Exception {
        HttpResponse<String> naPortaDaAplicacao = get("http://localhost:" + appPort + "/actuator/health");
        assertThat(naPortaDaAplicacao.statusCode()).isNotEqualTo(200);

        HttpResponse<String> liveness = get("http://localhost:" + managementPort + "/actuator/health/liveness");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");

        HttpResponse<String> readiness = get("http://localhost:" + managementPort + "/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void requisicaoDeSucessoGeraMetricasDeFluxoEPorAdaptadorELogSemDadoPessoal() throws Exception {
        Pedido pedido = PedidoFixtures.umPedido(TipoPessoa.FISICA, null, 100.0, Regiao.SUDESTE);

        HttpResponse<String> resposta = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + "/api/pedido/gerarNotaFiscal"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(pedido)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(200);

        String prometheus = get("http://localhost:" + managementPort + "/actuator/prometheus").body();
        assertThat(prometheus).contains("nota_fiscal_geracao_seconds_count");
        assertThat(prometheus).contains("nota_fiscal_itens_processados_count");
        assertThat(prometheus).contains("nota_fiscal_geradas_total");
        assertThat(prometheus).contains("nota_fiscal_falhas_total");
        assertThat(prometheus).contains("integracao_downstream_seconds_count{adapter=\"DynamoDbNotaFiscalProcessamentoAdapter\"");
        assertThat(prometheus).contains("resultado=\"sucesso\"");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getMDCPropertyMap)
                .anySatisfy(mdc -> assertThat(mdc).containsKeys("idPedido", "idNotaFiscal"));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(mensagem -> mensagem.contains("12345678900"))
                .noneMatch(mensagem -> mensagem.contains("Fixture de Teste"))
                .noneMatch(mensagem -> mensagem.contains("Rua Fixture"));
    }
}
