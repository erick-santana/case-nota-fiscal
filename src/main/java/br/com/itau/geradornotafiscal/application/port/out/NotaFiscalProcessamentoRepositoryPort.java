package br.com.itau.geradornotafiscal.application.port.out;

import br.com.itau.geradornotafiscal.application.outbox.NotaFiscalProcessamento;

/**
 * Outbox transacional (ADR-0001): grava o estado de processamento e o payload do evento de
 * domínio no mesmo item do DynamoDB. Este serviço só escreve — uma Lambda que ouve o DynamoDB
 * Streams desta tabela é quem publica no Kafka, fora deste código-fonte (RFC-0001).
 */
public interface NotaFiscalProcessamentoRepositoryPort {

    void salvar(NotaFiscalProcessamento registro);
}
