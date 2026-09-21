package br.com.itau.geradornotafiscal.application;

import br.com.itau.geradornotafiscal.application.outbox.NotaFiscalProcessamento;
import br.com.itau.geradornotafiscal.application.outbox.StatusProcessamento;
import br.com.itau.geradornotafiscal.application.port.out.IntegracaoDownstreamException;
import br.com.itau.geradornotafiscal.application.port.out.NotaFiscalProcessamentoRepositoryPort;
import br.com.itau.geradornotafiscal.domain.aliquota.CalculadoraAliquota;
import br.com.itau.geradornotafiscal.domain.frete.CalculadoraFrete;
import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import br.com.itau.geradornotafiscal.model.Pedido;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Caso de uso com o outbox mockado (SPEC-07): nenhuma chamada síncrona às quatro integrações
 * antigas permanece — a nota é montada e um único registro pendente é gravado no outbox.
 */
@ExtendWith(MockitoExtension.class)
class GerarNotaFiscalServiceTest {

    @Mock
    private CalculadoraAliquota calculadoraAliquota;
    @Mock
    private CalculadoraFrete calculadoraFrete;
    @Mock
    private NotaFiscalProcessamentoRepositoryPort outboxRepository;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private GerarNotaFiscalService service;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        service = new GerarNotaFiscalService(calculadoraAliquota, calculadoraFrete, outboxRepository, objectMapper, meterRegistry);
    }

    @Test
    void montaNotaFiscalComValoresDoDominioEGravaOutboxPendente() {
        Pedido pedido = pedido();
        List<ItemNotaFiscal> itensCalculados = List.of(ItemNotaFiscal.builder().idItem("1").valorTributoItem(12.0).build());
        when(calculadoraAliquota.calcular(any(), anyDouble(), any())).thenReturn(itensCalculados);
        when(calculadoraFrete.calcular(any(), anyDouble())).thenReturn(10.48);

        NotaFiscal notaFiscal = service.gerarNotaFiscal(pedido);

        assertThat(notaFiscal.getItens()).isEqualTo(itensCalculados);
        assertThat(notaFiscal.getValorFrete()).isEqualTo(10.48);
        assertThat(notaFiscal.getValorTotalItens()).isEqualTo(pedido.getValorTotalItens());
        assertThat(notaFiscal.getIdNotaFiscal()).isNotBlank();

        ArgumentCaptor<NotaFiscalProcessamento> registroCaptor = ArgumentCaptor.forClass(NotaFiscalProcessamento.class);
        verify(outboxRepository).salvar(registroCaptor.capture());
        NotaFiscalProcessamento registro = registroCaptor.getValue();
        assertThat(registro.getIdNotaFiscal()).isEqualTo(notaFiscal.getIdNotaFiscal());
        assertThat(registro.getStatus()).isEqualTo(StatusProcessamento.PENDENTE);
        assertThat(registro.getPayloadEvento()).contains(notaFiscal.getIdNotaFiscal());

        assertThat(meterRegistry.get("nota_fiscal_geradas_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("nota_fiscal_falhas_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("nota_fiscal_geracao_seconds").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("nota_fiscal_itens_processados").summary().totalAmount()).isEqualTo(1.0);
    }

    @Test
    void falhaAoGravarOutboxPropagaExcecaoENaoContaComoSucesso() {
        Pedido pedido = pedido();
        when(calculadoraAliquota.calcular(any(), anyDouble(), any())).thenReturn(List.of());
        when(calculadoraFrete.calcular(any(), anyDouble())).thenReturn(0.0);
        doThrow(new IntegracaoDownstreamException("dynamodb", "qualquer", new RuntimeException("falha simulada")))
                .when(outboxRepository).salvar(any());

        assertThatThrownBy(() -> service.gerarNotaFiscal(pedido))
                .isInstanceOf(IntegracaoDownstreamException.class);

        assertThat(meterRegistry.get("nota_fiscal_falhas_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("nota_fiscal_geradas_total").counter().count()).isEqualTo(0.0);
    }

    private Pedido pedido() {
        Destinatario destinatario = Destinatario.builder().build();
        Item item = new Item("1", "Item", 100.0, 1);
        return Pedido.builder()
                .valorTotalItens(100.0)
                .valorFrete(10.0)
                .itens(List.of(item))
                .destinatario(destinatario)
                .build();
    }
}
