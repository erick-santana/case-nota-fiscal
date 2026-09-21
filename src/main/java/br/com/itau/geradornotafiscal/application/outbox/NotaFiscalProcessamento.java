package br.com.itau.geradornotafiscal.application.outbox;

import java.time.Instant;

/**
 * Registro do outbox transacional (ADR-0001): {@code payloadEvento} é a {@code NotaFiscal}
 * serializada em JSON, gravada atomicamente com o status no mesmo item do DynamoDB.
 */
public class NotaFiscalProcessamento {

    private final String idNotaFiscal;
    private final String payloadEvento;
    private final StatusProcessamento status;
    private final Instant criadoEm;

    private NotaFiscalProcessamento(String idNotaFiscal, String payloadEvento, StatusProcessamento status, Instant criadoEm) {
        this.idNotaFiscal = idNotaFiscal;
        this.payloadEvento = payloadEvento;
        this.status = status;
        this.criadoEm = criadoEm;
    }

    public static NotaFiscalProcessamento pendente(String idNotaFiscal, String payloadEvento) {
        return new NotaFiscalProcessamento(idNotaFiscal, payloadEvento, StatusProcessamento.PENDENTE, Instant.now());
    }

    public String getIdNotaFiscal() {
        return idNotaFiscal;
    }

    public String getPayloadEvento() {
        return payloadEvento;
    }

    public StatusProcessamento getStatus() {
        return status;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }
}
