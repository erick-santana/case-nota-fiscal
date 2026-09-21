package br.com.itau.geradornotafiscal.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Destinatario {
	@JsonProperty("nome")
	@NotBlank
	private String nome;

	@JsonProperty("tipo_pessoa")
	@NotNull
	private TipoPessoa tipoPessoa;

	@JsonProperty("regime_tributacao")
	private RegimeTributacaoPJ regimeTributacao;

	@JsonProperty("documentos")
	@NotEmpty
	@Valid
	private List<Documento> documentos;

	// @NotEmpty, não: uma lista vazia é o caso de negócio legítimo "sem endereço de entrega" já
	// caracterizado em SPEC-02 (frete = 0 quando nenhum Endereco tem finalidade ENTREGA/COBRANCA_ENTREGA).
	// @NotNull sozinho já cobre a regressão de NPE de REQ-4.9 (enderecos ausente do payload).
	@JsonProperty("enderecos")
	@NotNull
	@Valid
	private List<Endereco> enderecos;

	@AssertTrue(message = "regime_tributacao é obrigatório quando tipo_pessoa é JURIDICA")
	@JsonIgnore
	public boolean isRegimeTributacaoInformadoParaJuridica() {
		return tipoPessoa != TipoPessoa.JURIDICA || regimeTributacao != null;
	}

}




