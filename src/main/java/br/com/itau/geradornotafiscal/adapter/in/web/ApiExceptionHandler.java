package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.adapter.in.web.ErroResposta.CampoInvalido;
import br.com.itau.geradornotafiscal.domain.aliquota.RegimeTributacaoNaoSuportadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RegimeTributacaoNaoSuportadoException.class)
    public ResponseEntity<ErroResposta> handleRegimeTributacaoNaoSuportado(RegimeTributacaoNaoSuportadoException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErroResposta.de(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErroResposta> handleCamposInvalidos(MethodArgumentNotValidException e) {
        List<CampoInvalido> campos = e.getBindingResult().getFieldErrors().stream()
                .map(erro -> new CampoInvalido(erro.getField(), erro.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ErroResposta.de(HttpStatus.BAD_REQUEST, "Payload inválido", campos));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErroResposta> handlePayloadMalformado(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ErroResposta.de(HttpStatus.BAD_REQUEST,
                "O corpo da requisição não pôde ser interpretado como um Pedido."));
    }
}
