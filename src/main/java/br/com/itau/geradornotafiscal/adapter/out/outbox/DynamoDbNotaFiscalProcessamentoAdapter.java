package br.com.itau.geradornotafiscal.adapter.out.outbox;

import br.com.itau.geradornotafiscal.application.outbox.NotaFiscalProcessamento;
import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.application.port.out.NotaFiscalProcessamentoRepositoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

/**
 * Tabela {@code nota_fiscal_processamento} (RFC-0001): chave única por {@code idNotaFiscal},
 * padrão ideal para DynamoDB. Só grava (PutItem) — quem publica no Kafka a partir daqui é uma
 * Lambda que ouve o DynamoDB Streams desta tabela, fora deste serviço.
 */
@Component
public class DynamoDbNotaFiscalProcessamentoAdapter implements NotaFiscalProcessamentoRepositoryPort {

    private static final String ATTR_ID = "idNotaFiscal";
    private static final String ATTR_PAYLOAD = "payloadEvento";
    private static final String ATTR_STATUS = "status";
    private static final String ATTR_CRIADO_EM = "criadoEm";

    private final DynamoDbClient client;
    private final String tabela;

    public DynamoDbNotaFiscalProcessamentoAdapter(DynamoDbClient client,
                                                   @Value("${app.outbox.dynamodb.table-name:nota_fiscal_processamento}") String tabela) {
        this.client = client;
        this.tabela = tabela;
    }

    @Override
    public void salvar(NotaFiscalProcessamento registro) {
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tabela)
                    .item(Map.of(
                            ATTR_ID, AttributeValue.fromS(registro.getIdNotaFiscal()),
                            ATTR_PAYLOAD, AttributeValue.fromS(registro.getPayloadEvento()),
                            ATTR_STATUS, AttributeValue.fromS(registro.getStatus().name()),
                            ATTR_CRIADO_EM, AttributeValue.fromS(registro.getCriadoEm().toString())))
                    .build());
        } catch (SdkException e) {
            throw new IntegracaoDownstreamException("dynamodb", registro.getIdNotaFiscal(), e);
        }
    }
}
