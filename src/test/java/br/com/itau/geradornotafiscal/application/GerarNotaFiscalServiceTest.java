package br.com.itau.geradornotafiscal.application;

import br.com.itau.geradornotafiscal.application.port.out.EntregaNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.EstoqueNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.FinanceiroNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.RegistroNotificacaoPort;
import br.com.itau.geradornotafiscal.domain.aliquota.CalculadoraAliquota;
import br.com.itau.geradornotafiscal.domain.frete.CalculadoraFrete;
import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.Item;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import br.com.itau.geradornotafiscal.model.Pedido;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Caso de uso com as quatro portas de saída mockadas: nenhuma latência real, verificação
 * determinística de que as quatro notificações são chamadas e de que uma falha isolada
 * produz a exceção agregada (D-02).
 */
@ExtendWith(MockitoExtension.class)
class GerarNotaFiscalServiceTest {

    @Mock
    private CalculadoraAliquota calculadoraAliquota;
    @Mock
    private CalculadoraFrete calculadoraFrete;
    @Mock
    private EstoqueNotificacaoPort estoquePort;
    @Mock
    private RegistroNotificacaoPort registroPort;
    @Mock
    private EntregaNotificacaoPort entregaPort;
    @Mock
    private FinanceiroNotificacaoPort financeiroPort;

    private ExecutorService notificacoesExecutor;
    private GerarNotaFiscalService service;

    @BeforeEach
    void setup() {
        notificacoesExecutor = Executors.newVirtualThreadPerTaskExecutor();
        service = new GerarNotaFiscalService(calculadoraAliquota, calculadoraFrete, estoquePort, registroPort,
                entregaPort, financeiroPort, notificacoesExecutor);
    }

    @AfterEach
    void tearDown() {
        notificacoesExecutor.close();
    }

    @Test
    void chamaAsQuatroPortasEMontaNotaFiscalComValoresDoDominio() {
        Pedido pedido = pedido();
        List<ItemNotaFiscal> itensCalculados = List.of(ItemNotaFiscal.builder().idItem("1").valorTributoItem(12.0).build());
        when(calculadoraAliquota.calcular(any(), anyDouble(), any())).thenReturn(itensCalculados);
        when(calculadoraFrete.calcular(any(), anyDouble())).thenReturn(10.48);

        NotaFiscal notaFiscal = service.gerarNotaFiscal(pedido);

        assertThat(notaFiscal.getItens()).isEqualTo(itensCalculados);
        assertThat(notaFiscal.getValorFrete()).isEqualTo(10.48);
        assertThat(notaFiscal.getValorTotalItens()).isEqualTo(pedido.getValorTotalItens());
        assertThat(notaFiscal.getIdNotaFiscal()).isNotBlank();

        verify(estoquePort, timeout(1000)).notificar(notaFiscal);
        verify(registroPort, timeout(1000)).notificar(notaFiscal);
        verify(entregaPort, timeout(1000)).agendar(notaFiscal);
        verify(financeiroPort, timeout(1000)).lancar(notaFiscal);
    }

    @Test
    void falhaDeUmaNotificacaoNaoImpedeAsOutrasEProduzExcecaoAgregada() {
        Pedido pedido = pedido();
        when(calculadoraAliquota.calcular(any(), anyDouble(), any())).thenReturn(List.of());
        when(calculadoraFrete.calcular(any(), anyDouble())).thenReturn(0.0);
        doThrow(new RuntimeException("falha simulada de estoque")).when(estoquePort).notificar(any());

        assertThatThrownBy(() -> service.gerarNotaFiscal(pedido))
                .isInstanceOf(NotificacoesParcialmenteFalharamException.class);

        verify(registroPort, timeout(1000)).notificar(any());
        verify(entregaPort, timeout(1000)).agendar(any());
        verify(financeiroPort, timeout(1000)).lancar(any());
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
