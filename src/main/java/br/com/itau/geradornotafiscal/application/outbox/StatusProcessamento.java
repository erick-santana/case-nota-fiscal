package br.com.itau.geradornotafiscal.application.outbox;

/**
 * Este serviço só grava {@code PENDENTE} (outbox transacional, ADR-0001). A publicação no
 * Kafka é feita por uma Lambda que ouve o DynamoDB Streams desta tabela — fora deste
 * código-fonte — e {@code PROCESSADA}/{@code FALHOU} são decisão do Saga coordinator sobre as
 * 4 confirmações de outcome, também fora de escopo aqui (ADR-0001, passos 4 e 5).
 */
public enum StatusProcessamento {
    PENDENTE
}
