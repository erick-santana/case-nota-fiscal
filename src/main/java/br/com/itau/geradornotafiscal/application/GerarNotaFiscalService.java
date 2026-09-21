package br.com.itau.geradornotafiscal.application;

import br.com.itau.geradornotafiscal.application.outbox.NotaFiscalProcessamento;
import br.com.itau.geradornotafiscal.application.port.in.GerarNotaFiscalUseCase;
import br.com.itau.geradornotafiscal.application.port.out.NotaFiscalProcessamentoRepositoryPort;
import br.com.itau.geradornotafiscal.domain.aliquota.CalculadoraAliquota;
import br.com.itau.geradornotafiscal.domain.frete.CalculadoraFrete;
import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import br.com.itau.geradornotafiscal.model.Pedido;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Desde SPEC-07, avisar Estoque/Registro/Entrega/Financeiro não é mais responsabilidade deste
 * serviço: ele grava o outbox no DynamoDB (ADR-0001) e devolve a nota. Uma Lambda que ouve o
 * DynamoDB Streams dessa tabela publica o evento no Kafka; os quatro sistemas consomem esse
 * tópico de forma assíncrona e independente — tudo fora deste código-fonte (RFC-0001).
 */
@Service
public class GerarNotaFiscalService implements GerarNotaFiscalUseCase {

    private static final Logger log = LoggerFactory.getLogger(GerarNotaFiscalService.class);
    private static final String MDC_ID_PEDIDO = "idPedido";
    private static final String MDC_ID_NOTA_FISCAL = "idNotaFiscal";

    private final CalculadoraAliquota calculadoraAliquota;
    private final CalculadoraFrete calculadoraFrete;
    private final NotaFiscalProcessamentoRepositoryPort outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Timer geracaoTimer;
    private final DistributionSummary itensProcessados;
    private final Counter notasGeradasTotal;
    private final Counter notasFalhasTotal;

    public GerarNotaFiscalService(CalculadoraAliquota calculadoraAliquota,
                                   CalculadoraFrete calculadoraFrete,
                                   NotaFiscalProcessamentoRepositoryPort outboxRepository,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.calculadoraAliquota = calculadoraAliquota;
        this.calculadoraFrete = calculadoraFrete;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.geracaoTimer = Timer.builder("nota_fiscal_geracao_seconds")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.itensProcessados = DistributionSummary.builder("nota_fiscal_itens_processados").register(meterRegistry);
        this.notasGeradasTotal = Counter.builder("nota_fiscal_geradas_total").register(meterRegistry);
        this.notasFalhasTotal = Counter.builder("nota_fiscal_falhas_total").register(meterRegistry);
    }

    @Override
    public NotaFiscal gerarNotaFiscal(Pedido pedido) {
        MDC.put(MDC_ID_PEDIDO, String.valueOf(pedido.getIdPedido()));
        Timer.Sample amostra = Timer.start(meterRegistry);
        try {
            NotaFiscal notaFiscal = montarNotaFiscalEPersistirOutbox(pedido);
            amostra.stop(geracaoTimer);
            itensProcessados.record(notaFiscal.getItens().size());
            notasGeradasTotal.increment();
            log.info("Nota fiscal gerada com sucesso");
            return notaFiscal;
        } catch (RuntimeException e) {
            amostra.stop(geracaoTimer);
            notasFalhasTotal.increment();
            log.error("Falha ao gerar nota fiscal: {}", e.getMessage());
            throw e;
        } finally {
            MDC.remove(MDC_ID_PEDIDO);
            MDC.remove(MDC_ID_NOTA_FISCAL);
        }
    }

    private NotaFiscal montarNotaFiscalEPersistirOutbox(Pedido pedido) {
        Destinatario destinatario = pedido.getDestinatario();

        List<ItemNotaFiscal> itens = calculadoraAliquota.calcular(
                destinatario, pedido.getValorTotalItens(), pedido.getItens());
        double valorFrete = calculadoraFrete.calcular(destinatario.getEnderecos(), pedido.getValorFrete());

        NotaFiscal notaFiscal = NotaFiscal.builder()
                .idNotaFiscal(UUID.randomUUID().toString())
                .data(LocalDateTime.now())
                .valorTotalItens(pedido.getValorTotalItens())
                .valorFrete(valorFrete)
                .itens(itens)
                .destinatario(destinatario)
                .build();

        MDC.put(MDC_ID_NOTA_FISCAL, notaFiscal.getIdNotaFiscal());
        persistirEventoDominio(notaFiscal);

        return notaFiscal;
    }

    private void persistirEventoDominio(NotaFiscal notaFiscal) {
        String payloadEvento = objectMapper.writeValueAsString(notaFiscal);
        outboxRepository.salvar(NotaFiscalProcessamento.pendente(notaFiscal.getIdNotaFiscal(), payloadEvento));
    }
}
