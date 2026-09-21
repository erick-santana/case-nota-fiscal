package br.com.itau.geradornotafiscal.adapter.in.web;

import org.springframework.http.HttpStatus;

import java.util.List;

public record ErroResposta(int status, String mensagem, List<CampoInvalido> campos) {

    public static ErroResposta de(HttpStatus status, String mensagem) {
        return new ErroResposta(status.value(), mensagem, List.of());
    }

    public static ErroResposta de(HttpStatus status, String mensagem, List<CampoInvalido> campos) {
        return new ErroResposta(status.value(), mensagem, campos);
    }

    public record CampoInvalido(String campo, String motivo) {
    }
}
