package br.com.itau.geradornotafiscal.application;

import br.com.itau.geradornotafiscal.application.port.in.GerarNotaFiscalUseCase;
import br.com.itau.geradornotafiscal.application.port.out.EntregaNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.EstoqueNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.FinanceiroNotificacaoPort;
import br.com.itau.geradornotafiscal.application.port.out.RegistroNotificacaoPort;
import br.com.itau.geradornotafiscal.domain.aliquota.CalculadoraAliquota;
import br.com.itau.geradornotafiscal.domain.frete.CalculadoraFrete;
import br.com.itau.geradornotafiscal.model.Destinatario;
import br.com.itau.geradornotafiscal.model.ItemNotaFiscal;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import br.com.itau.geradornotafiscal.model.Pedido;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

@Service
public class GerarNotaFiscalService implements GerarNotaFiscalUseCase {

    private final CalculadoraAliquota calculadoraAliquota;
    private final CalculadoraFrete calculadoraFrete;
    private final EstoqueNotificacaoPort estoquePort;
    private final RegistroNotificacaoPort registroPort;
    private final EntregaNotificacaoPort entregaPort;
    private final FinanceiroNotificacaoPort financeiroPort;
    private final ExecutorService notificacoesExecutor;

    public GerarNotaFiscalService(CalculadoraAliquota calculadoraAliquota,
                                   CalculadoraFrete calculadoraFrete,
                                   EstoqueNotificacaoPort estoquePort,
                                   RegistroNotificacaoPort registroPort,
                                   EntregaNotificacaoPort entregaPort,
                                   FinanceiroNotificacaoPort financeiroPort,
                                   ExecutorService notificacoesExecutor) {
        this.calculadoraAliquota = calculadoraAliquota;
        this.calculadoraFrete = calculadoraFrete;
        this.estoquePort = estoquePort;
        this.registroPort = registroPort;
        this.entregaPort = entregaPort;
        this.financeiroPort = financeiroPort;
        this.notificacoesExecutor = notificacoesExecutor;
    }

    @Override
    public NotaFiscal gerarNotaFiscal(Pedido pedido) {
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

        notificar(notaFiscal);

        return notaFiscal;
    }

    private void notificar(NotaFiscal notaFiscal) {
        CompletableFuture<?>[] notificacoes = {
                CompletableFuture.runAsync(() -> estoquePort.notificar(notaFiscal), notificacoesExecutor),
                CompletableFuture.runAsync(() -> registroPort.notificar(notaFiscal), notificacoesExecutor),
                CompletableFuture.runAsync(() -> entregaPort.agendar(notaFiscal), notificacoesExecutor),
                CompletableFuture.runAsync(() -> financeiroPort.lancar(notaFiscal), notificacoesExecutor)
        };

        try {
            CompletableFuture.allOf(notificacoes).join();
        } catch (CompletionException e) {
            throw NotificacoesParcialmenteFalharamException.de(notaFiscal, notificacoes);
        }
    }
}
