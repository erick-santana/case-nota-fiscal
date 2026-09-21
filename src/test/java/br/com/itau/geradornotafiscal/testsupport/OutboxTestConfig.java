package br.com.itau.geradornotafiscal.testsupport;

import br.com.itau.geradornotafiscal.application.port.out.NotaFiscalProcessamentoRepositoryPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Testes de MockMvc/{@code @SpringBootTest} exercitam o endpoint real, que agora grava o outbox
 * no DynamoDB (SPEC-07). Sem stub, esses testes deixariam de ser herméticos e passariam a exigir
 * uma tabela real (P4) — {@code DynamoDbNotaFiscalProcessamentoAdapterTest} já cobre o adapter
 * real contra um DynamoDB Local via Testcontainers.
 */
@TestConfiguration
public class OutboxTestConfig {

    @Bean
    @Primary
    NotaFiscalProcessamentoRepositoryPort notaFiscalProcessamentoRepositoryPort() {
        return registro -> { };
    }
}
