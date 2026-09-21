package br.com.itau.geradornotafiscal.adapter.out.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Cria a tabela se {@code app.outbox.dynamodb.auto-create-table=true} (perfil local/dev). Em
 * produção a tabela — e o DynamoDB Streams que alimenta a Lambda de publicação no Kafka — vem
 * de IaC (Terraform/CDK), fora deste serviço (P4, RFC-0001); a property fica desligada por
 * padrão para não fingir provisionar infraestrutura real a partir da aplicação.
 */
@Component
public class OutboxTableInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OutboxTableInitializer.class);

    private final DynamoDbClient client;
    private final String tabela;
    private final boolean autoCriar;

    public OutboxTableInitializer(DynamoDbClient client,
                                   @Value("${app.outbox.dynamodb.table-name:nota_fiscal_processamento}") String tabela,
                                   @Value("${app.outbox.dynamodb.auto-create-table:false}") boolean autoCriar) {
        this.client = client;
        this.tabela = tabela;
        this.autoCriar = autoCriar;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoCriar) {
            return;
        }
        try {
            client.createTable(CreateTableRequest.builder()
                    .tableName(tabela)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("idNotaFiscal").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("idNotaFiscal").keyType(KeyType.HASH).build())
                    .build());
            client.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(tabela).build());
            log.info("Tabela outbox '{}' criada", tabela);
        } catch (ResourceInUseException e) {
            log.debug("Tabela outbox '{}' já existe", tabela);
        }
    }
}
