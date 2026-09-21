package br.com.itau.geradornotafiscal.adapter.out.outbox;

import br.com.itau.geradornotafiscal.application.outbox.NotaFiscalProcessamento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contra um DynamoDB Local real (mesma imagem do docker-compose), não um mock do SDK — prova
 * que o item é gravado com os atributos esperados. A publicação a partir daqui (DynamoDB
 * Streams → Lambda → Kafka) é infraestrutura fora deste serviço, não coberta por este teste.
 */
@Testcontainers
class DynamoDbNotaFiscalProcessamentoAdapterTest {

    private static final String TABELA = "nota_fiscal_processamento_test";

    @Container
    private static final GenericContainer<?> DYNAMODB_LOCAL = new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    private DynamoDbClient client;
    private DynamoDbNotaFiscalProcessamentoAdapter adapter;

    @BeforeEach
    void setup() {
        client = DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://%s:%d".formatted(DYNAMODB_LOCAL.getHost(), DYNAMODB_LOCAL.getMappedPort(8000))))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        criarTabela();
        adapter = new DynamoDbNotaFiscalProcessamentoAdapter(client, TABELA);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void salvaOItemComOsAtributosDoRegistro() {
        NotaFiscalProcessamento registro = NotaFiscalProcessamento.pendente("nf-1", "{\"id_nota_fiscal\":\"nf-1\"}");

        adapter.salvar(registro);

        Map<String, AttributeValue> item = client.getItem(GetItemRequest.builder()
                        .tableName(TABELA)
                        .key(Map.of("idNotaFiscal", AttributeValue.fromS("nf-1")))
                        .build())
                .item();
        assertThat(item.get("idNotaFiscal").s()).isEqualTo("nf-1");
        assertThat(item.get("payloadEvento").s()).isEqualTo("{\"id_nota_fiscal\":\"nf-1\"}");
        assertThat(item.get("status").s()).isEqualTo("PENDENTE");
        assertThat(item.get("criadoEm").s()).isNotBlank();
    }

    private void criarTabela() {
        client.createTable(CreateTableRequest.builder()
                .tableName(TABELA)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("idNotaFiscal").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("idNotaFiscal").keyType(KeyType.HASH).build())
                .build());
    }
}
