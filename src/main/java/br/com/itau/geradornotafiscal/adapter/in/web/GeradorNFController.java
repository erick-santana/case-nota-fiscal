package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.application.port.in.GerarNotaFiscalUseCase;
import br.com.itau.geradornotafiscal.model.NotaFiscal;
import br.com.itau.geradornotafiscal.model.Pedido;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pedido")
public class GeradorNFController {

    private final GerarNotaFiscalUseCase gerarNotaFiscal;

    public GeradorNFController(GerarNotaFiscalUseCase gerarNotaFiscal) {
        this.gerarNotaFiscal = gerarNotaFiscal;
    }

    @Operation(summary = "Gera a nota fiscal (imposto + frete) para um pedido")
    @PostMapping("/gerarNotaFiscal")
    public ResponseEntity<NotaFiscal> gerarNotaFiscal(@Valid @RequestBody Pedido pedido) {
        return ResponseEntity.ok(gerarNotaFiscal.gerarNotaFiscal(pedido));
    }
}
