package br.com.itau.geradornotafiscal.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Endereco {
    @JsonProperty("cep")
    @NotBlank
    private String cep;

    @JsonProperty("logradouro")
    @NotBlank
    private String logradouro;

    @JsonProperty("numero")
    @NotBlank
    private String numero;

    @JsonProperty("estado")
    @NotBlank
    private String estado;

    @JsonProperty("complemento")
    private String complemento;

    @JsonProperty("finalidade")
    @NotNull
    private Finalidade finalidade;

    @JsonProperty("regiao")
    @NotNull
    private Regiao regiao;
}
