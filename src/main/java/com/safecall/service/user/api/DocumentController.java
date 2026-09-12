package com.safecall.service.user.api;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import io.swagger.v3.oas.annotations.Operation;
import tools.jackson.databind.json.JsonMapper;
import com.safecall.service.user.service.DocumentService;
import com.safecall.service.user.api.UserDtos.DocumentView;
@RestController
public class DocumentController {
	private final DocumentService service;private final JsonMapper mapper;
	public DocumentController(DocumentService service,JsonMapper mapper){this.service=service;this.mapper=mapper;}
	@GetMapping("/api/v1/documents/{code}") @Operation(operationId="U03",summary="동의 및 안내 문서 조회")
	public ResponseEntity<DocumentView> document(@PathVariable String code,@RequestParam(required=false) @Positive Integer version,WebRequest request)throws java.security.NoSuchAlgorithmException {
		var view=service.document(code,version);
		String etag="\""+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(view)))+"\"";
		if(request.checkNotModified(etag))return ResponseEntity.status(304).cacheControl(CacheControl.noCache().cachePublic()).eTag(etag).build();
		return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePublic()).eTag(etag).body(view);
	}
}
