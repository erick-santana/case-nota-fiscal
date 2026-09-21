package br.com.itau.geradornotafiscal.application.port.out;

public class IntegracaoDownstreamException extends RuntimeException {

    private final String integracao;
    private final String idNotaFiscal;

    public IntegracaoDownstreamException(String integracao, String idNotaFiscal, Throwable causa) {
        super("Falha na integração '%s' para a nota fiscal %s".formatted(integracao, idNotaFiscal), causa);
        this.integracao = integracao;
        this.idNotaFiscal = idNotaFiscal;
    }

    public String getIntegracao() {
        return integracao;
    }

    public String getIdNotaFiscal() {
        return idNotaFiscal;
    }
}
